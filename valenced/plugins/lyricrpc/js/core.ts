import { pickLine } from './lyrics'
import { composeStatus, MIN_CADENCE_SEC } from './style'
import type { LyricSet, Playback, Settings } from './types'

export type Context = {
	settings: Settings
	nowMs: number
	playback: Playback
	/** Lyric set for the current track, or null while the lookup is still running. */
	lyrics: LyricSet | null
	/** The last status this plugin wrote, or null when it is not holding the status. */
	written: string | null
	writtenAtMs: number
	/** The custom status text Discord holds right now; null when there is none set. */
	holding: string | null
	/** Whether Discord's status settings could be found at all. Nothing works without them. */
	canWrite: boolean
}

export type Decision =
	| { kind: 'hold'; reason: string }
	| { kind: 'clear'; reason: string }
	| { kind: 'write'; text: string; expiresAtMs: number; reason: string }

/** Where the song is now: the reported position plus the wall clock since it was reported. */
export function positionOf(playback: Playback, nowMs: number): number {
	if (!playback.track) return 0
	const moved = playback.playing ? Math.max(0, nowMs - playback.atMs) : 0
	const at = playback.positionMs + moved
	const duration = playback.track.durationMs
	return duration > 0 ? Math.min(at, duration) : at
}

/** Every status change is a presence update, so the gap between two writes is never short. */
export function cadenceMs(settings: Settings): number {
	const requested = Math.round(Number(settings.cadenceSec))
	const safe =
		Number.isFinite(requested) && requested > 0 ? requested : MIN_CADENCE_SEC
	return Math.max(MIN_CADENCE_SEC, safe) * 1000
}

/** How long a status may sit unrefreshed before it drops itself, for when the app is backgrounded. */
export function expiryMs(cadence: number): number {
	return Math.max(cadence * 3, 90_000)
}

const seconds = (ms: number): number => Math.round(ms / 1000)

export function decide(context: Context): Decision {
	const {
		settings,
		nowMs,
		playback,
		lyrics,
		written,
		writtenAtMs,
		holding,
		canWrite,
	} = context
	const gap = cadenceMs(settings)
	const wait = Math.max(0, gap - (nowMs - writtenAtMs))

	const clearOrHold = (reason: string): Decision =>
		written ? { kind: 'clear', reason } : { kind: 'hold', reason }

	if (!settings.enabled) return clearOrHold('turned off')
	if (!playback.track) return clearOrHold('nothing is playing in Spotify')
	if (!canWrite) {
		return {
			kind: 'hold',
			reason: 'Discord’s status settings are not loaded yet',
		}
	}

	const position = positionOf(playback, nowMs)

	if (!playback.playing) {
		return settings.clearOnPause
			? clearOrHold('Spotify is paused')
			: {
					kind: 'hold',
					reason: `paused at ${seconds(position)}s, keeping the line`,
				}
	}

	if (!lyrics || lyrics.key !== playback.track.key) {
		return { kind: 'hold', reason: 'looking up the lyric…' }
	}
	if (lyrics.failed || !lyrics.lines.length) {
		return clearOrHold(lyrics.failed || 'the lyric came back empty')
	}

	const line = pickLine(lyrics.lines, position)
	if (!line)
		return clearOrHold(`waiting for the first line (${seconds(position)}s in)`)

	const text = composeStatus({ line: line.text, settings })
	if (!text) return clearOrHold('the line is empty once styled')
	if (text === written) {
		return {
			kind: 'hold',
			reason: `the line has not changed (${seconds(line.t)}s in)`,
		}
	}
	if (wait > 0) {
		return {
			kind: 'hold',
			reason: `next write in ${Math.ceil(wait / 1000)}s: ${text}`,
		}
	}
	if (settings.respectManual && holding && holding !== written) {
		return { kind: 'hold', reason: `your own status says “${holding}”` }
	}

	return {
		kind: 'write',
		text,
		expiresAtMs: nowMs + expiryMs(gap),
		reason: lyrics.synced ? 'timed line' : 'evenly spread line',
	}
}
