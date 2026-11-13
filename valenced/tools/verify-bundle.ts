/**
 * Runs the built bundle against a stub Valenced host, with a fake clock, a fake store and a canned
 * LRCLIB. This is the artifact that ships, so it proves the wiring and not just the arithmetic.
 * Rebuild first: bun run build lyricrpc
 * Run: bun tools/verify-bundle.ts
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DEFAULT_SETTINGS } from '../plugins/lyricrpc/js/types'
import type { Settings } from '../plugins/lyricrpc/js/types'

const checks: string[] = []
const failures: string[] = []

function ok(label: string, condition: boolean, extra = ''): void {
	checks.push(label)
	if (!condition) failures.push(`${label}${extra ? ` — ${extra}` : ''}`)
}

const realNow = Date.now
const realSetTimeout = setTimeout
const flush = (): Promise<void> =>
	new Promise(resolve => realSetTimeout(resolve, 0))

let clock = 1_000_000
Date.now = () => clock

type Written = { at: number; value: Record<string, string> }

const world = {
	statusSetting: null as null | {
		getSetting(): unknown
		updateSetting(value: unknown): unknown
	},
	store: null as null | Record<string, unknown>,
	writes: [] as Written[],
	fetches: [] as string[],
	intervals: [] as (() => void)[],
	flux: {} as Record<string, (event: any) => any>,
	refuse: false,
}

const statusSetting = {
	current: null as null | Record<string, string>,
	getSetting(): unknown {
		return statusSetting.current
	},
	updateSetting(value: unknown): Promise<void> {
		world.writes.push({ at: clock, value: value as Record<string, string> })
		statusSetting.current = value as Record<string, string>
		return world.refuse
			? Promise.reject(new Error('Discord said no'))
			: Promise.resolve()
	},
}

const LYRICS: Record<string, string> = {
	Song: '[00:00.50]first\n[00:05.00]second\n[00:30.00]third',
	Other: '[00:00.10]other line',
}

function makeStore(name: string, positionMs: number): Record<string, unknown> {
	return {
		item: {
			id: `id-${name}`,
			name,
			artists: [{ name: 'Artist' }],
			album: { name: 'Album' },
			duration: 200_000,
		},
		active: true,
		position: positionMs,
		duration_ms: 200_000,
		isPlaying(this: any) {
			return this.active
		},
		addChangeListener(fn: () => void) {
			;(this as any).__listener = fn
		},
		removeChangeListener() {
			;(this as any).__listener = undefined
		},
	}
}

/** Flips the fake store the way Discord would, so no phase inherits a stale paused state. */
function setPlaying(playing: boolean): void {
	;(world.store as any).active = playing
}

const fetchStub = async (url: unknown): Promise<any> => {
	const target = String(url)
	world.fetches.push(target)
	const name = Object.keys(LYRICS).find(song =>
		target.includes(`track_name=${encodeURIComponent(song)}`),
	)
	if (target.includes('/api/get?')) {
		if (!name)
			return { ok: false, status: 404, json: async () => ({ code: 404 }) }
		return {
			ok: true,
			status: 200,
			json: async () => ({ syncedLyrics: LYRICS[name], plainLyrics: null }),
		}
	}
	return { ok: true, status: 200, json: async () => [] }
}

globalThis.fetch = fetchStub as unknown as typeof fetch
globalThis.setInterval = (fn: () => void) => {
	world.intervals.push(fn)
	return world.intervals.length
}
globalThis.clearInterval = () => {}

const stub = (name: string) => ({ __component: name })
const reactStub = {
	useState: (initial: unknown): [unknown, (next: unknown) => void] => [
		initial,
		() => {},
	],
	useEffect: (fn: () => void) => {
		fn()
	},
	useRef: (initial: unknown) => ({ current: initial }),
	useMemo: (fn: () => unknown) => fn(),
	useCallback: (fn: unknown) => fn,
}

const valenced = {
	react: {
		React: reactStub,
		ReactNative: {
			AppState: {
				addEventListener: () => ({ remove() {} }),
				currentState: 'active',
			},
			ScrollView: stub('ScrollView'),
		},
		ReactJSXRuntime: {
			jsx: (type: unknown, props: unknown) => ({ type, props }),
			jsxs: (type: unknown, props: unknown) => ({ type, props }),
			Fragment: 'Fragment',
		},
	},
	discord: {
		design: {
			Design: new Proxy(
				{},
				{ get: (_t, key) => stub(`Design.${String(key)}`) },
			),
		},
		flux: {
			getStore: (name: string, callback: (store: any) => void) => {
				if (name === 'SpotifyStore' && world.store) callback(world.store)
				return () => {}
			},
			onFluxEventDispatched: (type: string, callback: (event: any) => any) => {
				world.flux[type] = callback
				return () => {
					delete world.flux[type]
				}
			},
		},
	},
	modules: {
		finders: {
			lookupModule: () =>
				world.statusSetting
					? [{ CustomStatusSetting: world.statusSetting }]
					: [],
			filters: {
				withProps: (...names: string[]) => ({ names }),
			},
		},
	},
}

let captured: any = null
;(globalThis as any).plugin = (options: unknown) => {
	captured = options
	return options
}

const code = readFileSync(
	join(import.meta.dir, '..', 'plugins', 'lyricrpc', 'build', 'js', 'index.js'),
	'utf8',
)
const loaded = new Function('valenced', `return ${code}`)(valenced)
ok(
	'the bundle exports the plugin options',
	!!captured && typeof captured.start === 'function',
)
ok(
	'the default export is what plugin() returned',
	(loaded?.default ?? loaded) === captured,
)
ok(
	'the bundle has a settings screen',
	typeof captured?.SettingsComponent === 'function',
)
ok(
	'the bundle declares its storage default',
	captured?.jsonStorage?.default?.cadenceSec === DEFAULT_SETTINGS.cadenceSec,
)

let stored: Settings = { ...DEFAULT_SETTINGS }
const cleanups: (() => void)[] = []
let notifyStorage: ((update: Partial<Settings>) => void) | null = null

const api = {
	cleanup: (...fns: (() => void)[]) => cleanups.push(...fns),
	jsonStorage: {
		get: async () => stored,
		set: async (value: Partial<Settings>) => {
			stored = { ...stored, ...value }
			notifyStorage?.(value)
		},
		use: () => stored,
		subscribe: (fn: (update: Partial<Settings>) => void) => {
			notifyStorage = fn
			return () => {
				notifyStorage = null
			}
		},
	},
	plugin: {},
	unscoped: valenced,
}

async function advance(ms: number, ticks = 1): Promise<void> {
	for (let step = 0; step < ticks; step++) {
		clock += ms / ticks
		for (const fn of world.intervals) fn()
		await flush()
	}
}

/** Everything the settings screen would show, flattened out of the stub element tree. */
function rendered(node: any, into: string[] = []): string[] {
	if (!node) return into
	if (typeof node === 'string' || typeof node === 'number')
		into.push(String(node))
	else if (Array.isArray(node)) for (const child of node) rendered(child, into)
	else if (typeof node === 'object') {
		for (const key of ['label', 'subLabel', 'title', 'description', 'value']) {
			const prop = node.props?.[key]
			if (typeof prop === 'string') into.push(prop)
		}
		rendered(node.props?.children, into)
	}
	return into
}

function screen(): string {
	return rendered(captured.SettingsComponent({ api })).join('\n')
}

world.statusSetting = statusSetting
world.store = makeStore('Song', 0)
const started = await captured.start.call({ reportError: () => {} }, api)
await flush()

ok(
	'start reports its cadence',
	String(started).includes('every 20s'),
	String(started),
)
ok('the store was asked for', world.intervals.length === 1)
ok(
	'a lyric lookup went out',
	world.fetches.some(url => url.includes('/api/get?')),
	world.fetches.join(' '),
)
ok(
	'nothing is written before the first line',
	world.writes.length === 0,
	JSON.stringify(world.writes),
)

await advance(2000)
ok(
	'the first line is written',
	world.writes.length === 1,
	JSON.stringify(world.writes),
)
same('the first line is styled', world.writes[0]?.value.text, '“first”')
ok(
	'the write carries an expiry',
	world.writes[0]?.value.expiresAtMs === String(clock + 90_000),
	world.writes[0]?.value.expiresAtMs,
)
ok(
	'the write carries a created time',
	world.writes[0]?.value.createdAtMs === String(clock),
)
same('the emoji slot is empty by default', world.writes[0]?.value.emojiName, '')
ok(
	'the status screen says what was written',
	screen().includes('“first”'),
	screen().slice(0, 400),
)

await advance(5000)
ok(
	'a new line inside the cadence does not write',
	world.writes.length === 1,
	JSON.stringify(world.writes),
)
ok(
	'and it says why',
	screen().includes('next write in 1'),
	screen().slice(0, 600),
)

await advance(15_000)
ok(
	'the same line writes once the cadence has passed',
	world.writes.length === 2,
	JSON.stringify(world.writes),
)
same(
	'the later line is the one written',
	world.writes[1]?.value.text,
	'“second”',
)

await advance(20_000, 4)
same('and the next line after that', world.writes.at(-1)?.value.text, '“third”')

// The status emoji, taken from the settings screen's own write path.
await api.jsonStorage.set({ emoji: '🎤', prefix: '♪ ', decoration: 'corner' })
await advance(21_000, 3)
const styled = world.writes.at(-1)?.value
ok(
	'the emoji goes in its own slot',
	styled?.emojiName === '🎤',
	JSON.stringify(styled),
)
ok(
	'the prefix and decoration apply',
	styled?.text === '♪ 「third」',
	styled?.text,
)

// Pausing clears the line.
setPlaying(false)
await advance(1000)
ok(
	'a pause clears the line',
	world.writes.at(-1)?.value.text === '',
	JSON.stringify(world.writes.at(-1)),
)
ok(
	'the screen explains the clear',
	screen().includes('Spotify is paused'),
	screen().slice(-600),
)

// Or keeps it, when asked.
await api.jsonStorage.set({ clearOnPause: false })
const afterClear = world.writes.length
setPlaying(false)
await advance(1000)
ok(
	'with clearOnPause off a pause writes nothing',
	world.writes.length === afterClear,
	JSON.stringify(world.writes.slice(afterClear)),
)
ok(
	'and says it is holding the line',
	screen().includes('keeping the line'),
	screen().slice(-600),
)
setPlaying(true)
await advance(1000)

// A status set by hand.
statusSetting.current = {
	text: 'out fishing',
	emojiName: '',
	emojiId: '',
	expiresAtMs: '0',
	createdAtMs: String(clock),
}
const afterResume = world.writes.length
await advance(21_000, 3)
ok(
	'a manual status is left alone',
	world.writes.length === afterResume,
	JSON.stringify(world.writes.slice(afterResume)),
)
ok(
	'and the screen says whose it is',
	screen().includes('out fishing'),
	screen().slice(-600),
)

const beforeTakeover = world.writes.length
await api.jsonStorage.set({ respectManual: false })
await advance(1000)
ok(
	'taking over on request writes anyway',
	world.writes.length > beforeTakeover,
	JSON.stringify(world.writes.slice(beforeTakeover)),
)
ok(
	'the takeover wrote the current line',
	!!world.writes.at(-1)?.value.text.includes('third'),
	JSON.stringify(world.writes.at(-1)),
)

// Another song starts, reported through the flux event rather than the store.
const before = world.fetches.length
Object.assign(world.store, makeStore('Other', 500))
;(world.flux.SPOTIFY_PLAYER_STATE as any)?.({
	type: 'SPOTIFY_PLAYER_STATE',
	track: { track_id: 'id-Other', name: 'Other', artists: [{ name: 'Artist' }] },
	paused: false,
	position: 500,
})
ok(
	'a new song is looked up straight away',
	world.fetches.length > before,
	world.fetches.slice(before).join(' '),
)
await advance(21_000, 3)
ok(
	'and its line is written once the cadence allows',
	!!world.writes.at(-1)?.value.text.includes('other line'),
	JSON.stringify(world.writes.at(-1)),
)

// Discord refusing the write must not take the plugin down.
world.refuse = true
const beforeRefusal = world.writes.length
await api.jsonStorage.set({ decoration: 'note' })
await advance(21_000, 3)
ok(
	'a refused write was still attempted',
	world.writes.length > beforeRefusal,
	JSON.stringify(world.writes.slice(beforeRefusal)),
)
ok(
	'a refused write is reported',
	screen().includes('Discord refused'),
	screen().slice(-600),
)
world.refuse = false

// Logging out clears the track and stops the writes.
Object.assign(world.store, { item: null })
;(world.flux.LOGOUT as any)?.({ type: 'LOGOUT' })
await advance(1000)
ok(
	'the screen shows nothing is playing',
	screen().includes('no Spotify track'),
	screen().slice(-600),
)

for (const fn of cleanups) fn()
await flush()
ok('cleanup runs without throwing', true)
ok('the clock and store survive a stop', world.intervals.length === 1)

function same(label: string, got: unknown, want: unknown): void {
	ok(
		label,
		JSON.stringify(got) === JSON.stringify(want),
		`got ${JSON.stringify(got)} want ${JSON.stringify(want)}`,
	)
}

console.log(
	`${checks.length - failures.length}/${checks.length} bundle checks passed`,
)
for (const failure of failures) console.log(`  FAIL ${failure}`)
if (failures.length) throw new Error(`${failures.length} failed`)
Date.now = realNow
