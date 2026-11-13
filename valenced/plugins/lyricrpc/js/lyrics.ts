import type { LyricLine, LyricSet, Track } from './types'

const API = 'https://lrclib.net/api'
const CACHE_LIMIT = 60
/** Tracks remembered between plays, so a repeated song costs no request. */
const cache = new Map<string, LyricSet>()

const stamp = /^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/

function toMs(minutes: number, seconds: number, fraction?: string): number {
	const ms = fraction
		? fraction.length >= 3
			? Number(fraction.slice(0, 3))
			: Number(fraction) * 10
		: 0
	return minutes * 60_000 + seconds * 1000 + (Number.isFinite(ms) ? ms : 0)
}

/** Reads `[mm:ss.xx]` lines, including the multi-stamp `[00:01.00][00:21.00]` kind. */
export function parseSynced(raw: string): LyricLine[] {
	const lines: LyricLine[] = []
	for (const row of raw.split(/\r?\n/)) {
		const starts: number[] = []
		let rest = row
		for (;;) {
			const found = stamp.exec(rest)
			if (!found) break
			starts.push(toMs(Number(found[1]), Number(found[2]), found[3]))
			rest = rest.slice(found[0].length)
		}
		const text = rest.trim()
		if (!starts.length || !text) continue
		for (const t of starts) lines.push({ t, text })
	}
	return lines.sort((a, b) => a.t - b.t)
}

/**
 * Unsynced lyrics have words but no clock. Spreading them evenly is a guess, so it is only done when
 * the user asked for it, and it is reported as unsynced in the diagnostics.
 */
export function spreadUnsynced(raw: string, durationMs: number): LyricLine[] {
	const texts = raw
		.split(/\r?\n/)
		.map(row => row.trim())
		.filter(Boolean)
	if (!texts.length) return []
	const step = (durationMs > 0 ? durationMs : 210_000) / texts.length
	return texts.map((text, index) => ({ t: Math.round(index * step), text }))
}

/** The line being sung at `positionMs`: the last one that has already started. */
export function pickLine(
	lines: LyricLine[],
	positionMs: number,
): LyricLine | null {
	let found: LyricLine | null = null
	for (const line of lines) {
		if (line.t > positionMs) break
		found = line
	}
	return found
}

function query(fields: Record<string, string | number | undefined>): string {
	return Object.entries(fields)
		.filter(([, value]) => value !== undefined && `${value}`.length)
		.map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
		.join('&')
}

export function urlsFor(track: Track): { exact: string; search: string } {
	const duration =
		track.durationMs > 0 ? Math.round(track.durationMs / 1000) : undefined
	return {
		exact: `${API}/get?${query({ track_name: track.name, artist_name: track.artists, album_name: track.album, duration })}`,
		search: `${API}/search?${query({ track_name: track.name, artist_name: track.artists, duration })}`,
	}
}

async function json(url: string, signal?: AbortSignal): Promise<unknown> {
	const response = await fetch(url, {
		headers: { accept: 'application/json' },
		signal,
	})
	if (response.status === 404) return null
	if (!response.ok) throw new Error(`LRCLIB answered ${response.status}`)
	return response.json()
}

type Candidate = { syncedLyrics?: string | null; plainLyrics?: string | null }

function take(found: Candidate, track: Track): LyricSet | null {
	const synced = parseSynced(String(found.syncedLyrics || ''))
	if (synced.length) {
		return {
			key: track.key,
			lines: synced,
			synced: true,
			source: 'LRCLIB timed',
		}
	}
	return null
}

export function forgetLyrics(): void {
	cache.clear()
}

function remember(set: LyricSet): LyricSet {
	cache.delete(set.key)
	cache.set(set.key, set)
	while (cache.size > CACHE_LIMIT) {
		const oldest = cache.keys().next()
		if (oldest.done) break
		cache.delete(oldest.value)
	}
	return set
}

/** Ask LRCLIB for this track's lyrics, exactly first and then by search. */
export async function lookup(
	track: Track,
	options: { useUnsynced: boolean; signal?: AbortSignal },
): Promise<LyricSet> {
	const cached = cache.get(track.key)
	if (cached) {
		cache.delete(track.key)
		cache.set(track.key, cached)
		return cached
	}

	const urls = urlsFor(track)
	const exact = (await json(urls.exact, options.signal)) as Candidate | null
	let found: Candidate | null = exact
	if (!found || (!found.syncedLyrics && !found.plainLyrics)) {
		const results = (await json(urls.search, options.signal)) as
			| Candidate[]
			| null
		const list = Array.isArray(results) ? results : []
		found =
			list.find(row => row?.syncedLyrics) ||
			list.find(row => row?.plainLyrics) ||
			null
	}

	if (!found) {
		return remember({
			key: track.key,
			lines: [],
			synced: false,
			source: 'LRCLIB has no entry',
			failed: `No lyric for “${track.name}”`,
		})
	}

	const timed = take(found, track)
	if (timed) return remember(timed)

	const plain = String(found.plainLyrics || '')
	if (options.useUnsynced && plain.trim()) {
		const lines = spreadUnsynced(plain, track.durationMs)
		if (lines.length) {
			return remember({
				key: track.key,
				lines,
				synced: false,
				source: `LRCLIB words, evenly spread (${lines.length} lines)`,
			})
		}
	}

	return remember({
		key: track.key,
		lines: [],
		synced: false,
		source: 'LRCLIB has no timed lyric',
		failed: `No timed lyric for “${track.name}”`,
	})
}
