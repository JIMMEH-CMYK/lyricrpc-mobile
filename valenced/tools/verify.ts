/**
 * Headless checks for the parts of the plugin that carry no Discord code in them.
 * Run: bun tools/verify.ts
 */

import {
	cadenceMs,
	decide,
	expiryMs,
	positionOf,
} from '../plugins/lyricrpc/js/core'
import {
	forgetLyrics,
	lookup,
	parseSynced,
	pickLine,
	spreadUnsynced,
	urlsFor,
} from '../plugins/lyricrpc/js/lyrics'
import { readPlayback } from '../plugins/lyricrpc/js/playback'
import {
	applyCasing,
	censorWords,
	composeStatus,
	remaining,
	STATUS_LIMIT,
	splitList,
	trimToUnits,
} from '../plugins/lyricrpc/js/style'
import { DEFAULT_SETTINGS } from '../plugins/lyricrpc/js/types'
import type { Context } from '../plugins/lyricrpc/js/core'
import type { LyricSet, Settings, Track } from '../plugins/lyricrpc/js/types'

let checks = 0
const failures: string[] = []

function ok(label: string, condition: boolean, extra = ''): void {
	checks++
	if (!condition) failures.push(`${label}${extra ? ` — ${extra}` : ''}`)
}

function same(label: string, got: unknown, want: unknown): void {
	ok(
		label,
		JSON.stringify(got) === JSON.stringify(want),
		`got ${JSON.stringify(got)}`,
	)
}

const track: Track = {
	key: 't1|Artist',
	name: 'Song',
	artists: 'Artist',
	album: 'Album',
	durationMs: 200_000,
}

const lyric = (lines: { t: number; text: string }[]): LyricSet => ({
	key: track.key,
	lines,
	synced: true,
	source: 'test',
})

const base: Context = {
	settings: DEFAULT_SETTINGS,
	nowMs: 100_000,
	playback: { track, playing: true, positionMs: 60_000, atMs: 100_000 },
	lyrics: lyric([
		{ t: 5_000, text: 'first' },
		{ t: 55_000, text: 'second' },
		{ t: 95_000, text: 'third' },
	]),
	written: null,
	writtenAtMs: 0,
	holding: null,
	canWrite: true,
}

const contextWith = (changes: Partial<Context>): Context => ({
	...base,
	...changes,
})
const settingsWith = (changes: Partial<Settings>): Settings => ({
	...DEFAULT_SETTINGS,
	...changes,
})

// ---- LRC parsing -------------------------------------------------------------

same('parses centiseconds', parseSynced('[00:12.34]hello'), [
	{ t: 12_340, text: 'hello' },
])
same('parses milliseconds', parseSynced('[00:12.345]hi'), [
	{ t: 12_345, text: 'hi' },
])
same('parses bare seconds', parseSynced('[01:02]hi'), [
	{ t: 62_000, text: 'hi' },
])
same(
	'keeps multi-stamp lines twice',
	parseSynced('[00:20.00][01:20.00]chorus').length,
	2,
)
same(
	'sorts out of order',
	parseSynced('[00:30.00]b\n[00:10.00]a').map(l => l.text),
	['a', 'b'],
)
same('drops metadata', parseSynced('[ar:Artist]\n[ti:Song]\n[00:01.00]real'), [
	{ t: 1000, text: 'real' },
])
same('drops empty lines', parseSynced('[00:01.00]   \n[00:02.00]x'), [
	{ t: 2000, text: 'x' },
])
same(
	'survives CRLF and no trailing newline',
	parseSynced('[00:01.00]a\r\n[00:02.00]b').length,
	2,
)
same(
	'keeps brackets inside the words',
	parseSynced('[00:01.00]see [later]')[0].text,
	'see [later]',
)

// ---- line selection ----------------------------------------------------------

ok(
	'nothing before the first line',
	pickLine([{ t: 5000, text: 'a' }], 4999) === null,
)
same('exactly on a line', pickLine([{ t: 5000, text: 'a' }], 5000)?.text, 'a')
same(
	'holds until the next',
	pickLine(
		[
			{ t: 5000, text: 'a' },
			{ t: 9000, text: 'b' },
		],
		8999,
	)?.text,
	'a',
)
same(
	'past the end stays on the last',
	pickLine([{ t: 5000, text: 'a' }], 600_000)?.text,
	'a',
)

// ---- styling -----------------------------------------------------------------

same('casing upper', applyCasing('oh hi', 'upper'), 'OH HI')
same('casing lower', applyCasing('OH HI', 'lower'), 'oh hi')
same(
	'casing title',
	applyCasing('we are YOUNG tonight', 'title'),
	'We Are Young Tonight',
)
same('casing keep', applyCasing('miXeD', 'keep'), 'miXeD')
same('censor masks the middle', censorWords('baby baby', ['baby']), 'b••• b•••')
same('censor is caseless', censorWords('BaBy', ['baby']), 'B•••')
same(
	'censor misses leave it alone',
	censorWords('nothing here', ['baby']),
	'nothing here',
)
same(
	'censor of two words',
	censorWords('one two one', ['one', 'two']),
	'o•• t•• o••',
)
same('split list', splitList(' a, b ; c\nd '), ['a', 'b', 'c', 'd'])

same(
	'decoration quotes',
	composeStatus({
		line: 'hi',
		settings: settingsWith({ decoration: 'quote' }),
	}),
	'“hi”',
)
same(
	'decoration plain',
	composeStatus({ line: 'hi', settings: settingsWith({ decoration: 'none' }) }),
	'hi',
)
same(
	'prefix and suffix wrap the decoration',
	composeStatus({
		line: 'hi',
		settings: settingsWith({
			prefix: '♪ ',
			suffix: ' · x',
			decoration: 'corner',
		}),
	}),
	'♪ 「hi」 · x',
)
same(
	'styling order is case then censor',
	composeStatus({
		line: 'Baby',
		settings: settingsWith({
			casing: 'upper',
			censor: 'baby',
			decoration: 'none',
		}),
	}),
	'B•••',
)
same(
	'an empty line yields nothing',
	composeStatus({ line: '   ', settings: DEFAULT_SETTINGS }),
	'',
)

ok(
	'an unknown decoration still wraps',
	composeStatus({
		line: 'x',
		settings: { ...DEFAULT_SETTINGS, decoration: 'nope' as never },
	}).includes('x'),
)

// ---- the 128 code unit budget --------------------------------------------------

same('emoji counts as two units', trimToUnits('🎵'.repeat(2), 3), '🎵')
same(
	'a pair that will not fit is left whole out',
	trimToUnits(`${'a'.repeat(127)}🎵`, 128),
	'a'.repeat(127),
)
ok(
	'no lone surrogate ever leaves',
	!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(
		trimToUnits('🎵'.repeat(200), 128),
	),
)
ok(
	'a long line is cut to the limit',
	trimToUnits('x'.repeat(400), STATUS_LIMIT).length === STATUS_LIMIT,
	`${trimToUnits('x'.repeat(400), STATUS_LIMIT).length}`,
)
const long = composeStatus({
	line: 'y'.repeat(300),
	settings: settingsWith({ prefix: 'z'.repeat(10) }),
})
ok(
	'composed status never exceeds 128 units',
	long.length === STATUS_LIMIT,
	`${long.length}`,
)
same('remaining', remaining('abc'), STATUS_LIMIT - 3)

// ---- cadence -------------------------------------------------------------------

same('cadence floor', cadenceMs(settingsWith({ cadenceSec: 2 })), 15_000)
same('cadence as asked', cadenceMs(settingsWith({ cadenceSec: 45 })), 45_000)
same(
	'cadence of garbage',
	cadenceMs(settingsWith({ cadenceSec: Number.NaN })),
	15_000,
)
same('cadence of zero', cadenceMs(settingsWith({ cadenceSec: 0 })), 15_000)
same('expiry is at least the floor', expiryMs(15_000), 90_000)
same('expiry grows with a long cadence', expiryMs(60_000), 180_000)

// ---- position ------------------------------------------------------------------

same(
	'position moves with the clock',
	positionOf({ track, playing: true, positionMs: 1000, atMs: 0 }, 4000),
	5000,
)
same(
	'position holds while paused',
	positionOf({ track, playing: false, positionMs: 1000, atMs: 0 }, 4000),
	1000,
)
same(
	'position clamps to the song',
	positionOf({ track, playing: true, positionMs: 199_000, atMs: 0 }, 60_000),
	200_000,
)
same(
	'no track is position zero',
	positionOf({ track: null, playing: false, positionMs: 9, atMs: 0 }, 9),
	0,
)

// ---- the decision table --------------------------------------------------------

same(
	'off clears',
	decide(
		contextWith({ settings: settingsWith({ enabled: false }), written: 'x' }),
	).kind,
	'clear',
)
same(
	'off with nothing written holds',
	decide(contextWith({ settings: settingsWith({ enabled: false }) })).kind,
	'hold',
)
same(
	'nothing playing clears',
	decide(
		contextWith({
			playback: { track: null, playing: false, positionMs: 0, atMs: 0 },
			written: 'x',
		}),
	).kind,
	'clear',
)
same(
	'unreadable settings hold',
	decide(contextWith({ canWrite: false })).kind,
	'hold',
)
same(
	'paused clears by default',
	decide(
		contextWith({
			playback: { ...base.playback, playing: false },
			written: 'x',
		}),
	).kind,
	'clear',
)
same(
	'paused keeps when asked',
	decide(
		contextWith({
			settings: settingsWith({ clearOnPause: false }),
			playback: { ...base.playback, playing: false },
			written: 'x',
		}),
	).kind,
	'hold',
)
same('no lyric yet holds', decide(contextWith({ lyrics: null })).kind, 'hold')
same(
	'lyric for the old song holds',
	decide(contextWith({ lyrics: { ...lyric([]), key: 'other' } })).kind,
	'hold',
)
same(
	'a lookup that failed clears',
	decide(
		contextWith({
			lyrics: {
				key: track.key,
				lines: [],
				synced: false,
				source: '',
				failed: 'no lyric',
			},
			written: 'x',
		}),
	).kind,
	'clear',
)
same(
	'before the first line holds when there is nothing to clear',
	decide(
		contextWith({
			nowMs: 1000,
			playback: { track, playing: true, positionMs: 1000, atMs: 1000 },
		}),
	).kind,
	'hold',
)
same(
	'before the first line clears what is there',
	decide(
		contextWith({
			nowMs: 1000,
			written: '“third”',
			playback: { track, playing: true, positionMs: 1000, atMs: 1000 },
		}),
	).kind,
	'clear',
)
same(
	'the line itself',
	(
		decide(contextWith({ lyrics: lyric([{ t: 55_000, text: 'second' }]) })) as {
			text?: string
		}
	).text,
	'“second”',
)
same(
	'the same line does not re-write',
	decide(contextWith({ written: '“second”' })).kind,
	'hold',
)
same(
	'the cadence gates a new line',
	decide(contextWith({ written: '“first”', writtenAtMs: 95_000 })).kind,
	'hold',
)
same(
	'a manual status is left alone',
	decide(contextWith({ holding: 'my own status' })).kind,
	'hold',
)
same(
	'a manual status is left alone even with nothing written',
	(decide(contextWith({ holding: 'my own status' })) as { reason?: string })
		.reason,
	'your own status says “my own status”',
)
same(
	'our own status is not manual',
	decide(contextWith({ written: '“first”', holding: '“first”' })).kind,
	'write',
)
same(
	'taking over is allowed when asked',
	decide(
		contextWith({
			settings: settingsWith({ respectManual: false }),
			holding: 'mine now',
		}),
	).kind,
	'write',
)
const written = decide(contextWith({})) as {
	kind: string
	expiresAtMs?: number
}
same('a plain write happens', written.kind, 'write')
same('a write carries an expiry', written.expiresAtMs, 100_000 + 90_000)
same(
	'an emoji-only manual status is still taken over',
	decide(contextWith({ holding: '' })).kind,
	'write',
)

// ---- the store and the event payload -------------------------------------------

const storeShaped = readPlayback({
	item: {
		id: 't1',
		name: 'Song',
		artists: [{ name: 'Artist' }],
		album: { name: 'Album' },
		duration: 200_000,
	},
	active: true,
	getSyncedPosition: () => 1234,
	getSyncedDuration: () => 200_000,
	isPlaying: () => true,
	addChangeListener: () => {},
})
same('store name', storeShaped.track?.name, 'Song')
same('store artists joined', storeShaped.track?.artists, 'Artist')
same('store position from the getter', storeShaped.positionMs, 1234)
same('store playing from the getter', storeShaped.playing, true)

const eventShaped = readPlayback({
	type: 'SPOTIFY_PLAYER_STATE',
	track: {
		track_id: 't2',
		name: 'Other',
		artists: [{ name: 'A' }, { name: 'B' }],
		album: { name: 'Alb' },
	},
	paused: false,
	position: 4321,
	duration_ms: 90_000,
	device: { id: 'd', is_active: true },
})
same('event name', eventShaped.track?.name, 'Other')
same('event artists joined', eventShaped.track?.artists, 'A, B')
same('event position', eventShaped.positionMs, 4321)
same('event duration', eventShaped.track?.durationMs, 90_000)
same('event playing from paused', eventShaped.playing, true)
ok(
	'event key differs from store key',
	eventShaped.track?.key !== storeShaped.track?.key,
)

same(
	'a null track is nothing playing',
	readPlayback({ type: 'SPOTIFY_PLAYER_STATE', track: null }).track,
	null,
)
same(
	'a paused payload is paused',
	readPlayback({ track: { name: 'x' }, paused: true }).playing,
	false,
)
same('an empty object is nothing', readPlayback({}).track, null)
same(
	'a missing name is nothing',
	readPlayback({ item: { artists: [] } }).track,
	null,
)
same('not an object is nothing', readPlayback(undefined).track, null)
same(
	'a device is enough to call it playing',
	readPlayback({ item: { name: 'x' }, device: { is_active: true } }).playing,
	true,
)
same(
	'a track with no id still gets a key',
	readPlayback({ track: { name: 'x', artists: [{ name: 'y' }] } }).track?.key,
	'x|y',
)

// The clock stamp, which is what makes a line advance while a song plays.
const heldNow = Date.now
Date.now = () => 5000
const staticStore = {
	item: { id: 's', name: 'x', artists: [] },
	position: 5000,
}
const stamped = readPlayback(staticStore)
Date.now = () => 9000
same(
	'a re-read of a plain position keeps the older stamp',
	readPlayback(staticStore, stamped).atMs,
	5000,
)
same(
	'a new song gets a fresh stamp',
	readPlayback(
		{ item: { id: 'n', name: 'x', artists: [] }, position: 5000 },
		stamped,
	).atMs,
	9000,
)
same(
	'a getter is a live position and re-stamps',
	readPlayback({ ...staticStore, getSyncedPosition: () => 7000 }, stamped).atMs,
	9000,
)
Date.now = heldNow

// ---- the LRCLIB query ----------------------------------------------------------

const urls = urlsFor(track)
ok(
	'exact url',
	urls.exact.startsWith('https://lrclib.net/api/get?'),
	urls.exact,
)
ok('exact url carries the song', urls.exact.includes('track_name=Song'))
ok('exact url carries the length', urls.exact.includes('duration=200'))
same(
	'a track with no length asks for none',
	urlsFor({ ...track, durationMs: 0 }).exact.includes('duration='),
	false,
)
ok(
	'the query is escaped',
	urlsFor({ ...track, name: 'A & B (Live)' }).exact.includes(
		'track_name=A%20%26%20B%20(Live)',
	),
	urlsFor({ ...track, name: 'A & B (Live)' }).exact,
)

same('spread of three lines over a minute', spreadUnsynced('a\nb\nc', 60_000), [
	{ t: 0, text: 'a' },
	{ t: 20_000, text: 'b' },
	{ t: 40_000, text: 'c' },
])
same(
	'spread with no known length still spaces',
	spreadUnsynced('a\nb', 0)[1].t,
	105_000,
)
same('spread of nothing', spreadUnsynced('  \n ', 1000), [])

// ---- against the real API ------------------------------------------------------

async function live(): Promise<void> {
	const real: Track = {
		key: 'live|never gonna',
		name: 'Never Gonna Give You Up',
		artists: 'Rick Astley',
		album: 'Whenever You Need Somebody',
		durationMs: 213_000,
	}
	forgetLyrics()
	try {
		const found = await lookup(real, { useUnsynced: true })
		ok('LRCLIB answered', found.lines.length > 0, found.failed || found.source)
		ok(
			'LRCLIB timings are sane',
			found.lines.every(
				line => Number.isFinite(line.t) && line.text.length > 0,
			),
		)
		const again = await lookup(real, { useUnsynced: true })
		ok('the second lookup is the cached one', again === found)
	} catch (error) {
		failures.push(
			`LRCLIB lookup threw: ${error instanceof Error ? error.message : String(error)}`,
		)
		checks++
	}

	forgetLyrics()
	const nonsense: Track = {
		key: 'live|nonsense',
		name: 'Zxzq Wqvpl Kdmvjof Tsvjjj Xyz 918273',
		artists: 'Qqplll Zzzkkk',
		album: '',
		durationMs: 123_000,
	}
	try {
		const none = await lookup(nonsense, { useUnsynced: true })
		ok(
			'a song nobody has comes back with no lines',
			none.lines.length === 0,
			JSON.stringify(none).slice(0, 160),
		)
		ok(
			'a song nobody has says so',
			typeof none.failed === 'string' && none.failed.length > 0,
		)
	} catch (error) {
		failures.push(
			`missing-lookup threw: ${error instanceof Error ? error.message : String(error)}`,
		)
		checks++
	}
}

await live()

console.log(`${checks - failures.length}/${checks} checks passed`)
for (const failure of failures) console.log(`  FAIL ${failure}`)
if (failures.length) throw new Error(`${failures.length} failed`)
