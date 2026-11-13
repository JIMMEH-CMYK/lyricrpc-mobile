import { cadenceMs, decide, positionOf } from './core'
import { lookup } from './lyrics'
import SettingsScreen from './Settings'
import { sync, watchSpotify } from './spotify'
import { note, patch, read, resetDiagnostics } from './state'
import {
	clearCustom,
	customStatusSetting,
	forgetStatusSetting,
	readText,
	writeCustom,
} from './status'
import { DEFAULT_SETTINGS } from './types'
import type { Decision } from './core'
import type { LyricSet, Settings, Track } from './types'

export const VERSION = '1.0.0'

/** How often the engine looks at the store and decides whether the line moved. */
const TICK_MS = 1000
/** A failed lyric lookup is tried again after this, so one dropped request is not permanent. */
const RETRY_MS = 45_000

function message(error: unknown): string {
	if (error instanceof Error) return error.message
	const code = (error as { name?: unknown; status?: unknown } | null)?.name
	const status = (error as { status?: unknown } | null)?.status
	return String(code || status || error)
}

export default plugin<{ jsonStorage: Settings }>({
	jsonStorage: { load: true, default: DEFAULT_SETTINGS },
	SettingsComponent: SettingsScreen,

	async start({ cleanup, jsonStorage }) {
		let settings: Settings = {
			...DEFAULT_SETTINGS,
			...(await jsonStorage.get()),
		}
		let lyrics: LyricSet | null = null
		/** The track whose lookup is in flight. */
		let wanted = ''
		let retryAt = 0
		let controller: AbortController | null = null
		let written: string | null = null
		let writtenAt = 0
		let busy = false

		patch({
			running: true,
			startedAt: Date.now(),
			settings,
			held: 'waiting for Spotify',
		})

		const load = async (track: Track | null): Promise<void> => {
			if (!track) {
				lyrics = null
				wanted = ''
				return
			}
			if (wanted === track.key || lyrics?.key === track.key) return
			controller?.abort()
			const own = new AbortController()
			controller = own
			wanted = track.key
			lyrics = null
			patch({ lines: 0, synced: false, source: 'looking up…' })
			try {
				const found = await lookup(track, {
					useUnsynced: settings.useUnsynced,
					signal: own.signal,
				})
				if (wanted !== track.key) return
				lyrics = found
				wanted = ''
				patch({
					lines: found.lines.length,
					synced: found.synced,
					source: found.source,
				})
			} catch (error) {
				if (wanted !== track.key) return
				wanted = ''
				retryAt = Date.now() + RETRY_MS
				const why = message(error)
				lyrics = {
					key: track.key,
					lines: [],
					synced: false,
					source: 'LRCLIB',
					failed: why,
				}
				note('error', `lyric lookup: ${why}`)
			}
		}

		const apply = (decision: Decision): void => {
			const now = Date.now()
			if (decision.kind === 'hold') return

			if (decision.kind === 'clear') {
				const refused = clearCustom(error =>
					note('error', `Discord refused the clear (${message(error)})`),
				)
				if (!refused) return
				note('clear', decision.reason)
				written = null
				writtenAt = now
				patch({ written: '' })
				return
			}

			const accepted = writeCustom(
				{
					text: decision.text,
					emojiName: settings.emoji.trim(),
					emojiId: '',
					expiresAtMs: String(decision.expiresAtMs),
					createdAtMs: String(now),
				},
				error =>
					note('error', `Discord refused the status (${message(error)})`),
			)
			if (!accepted) {
				note('error', 'Discord’s status setting would not take the write')
				return
			}
			written = decision.text
			writtenAt = now
			patch({ written: decision.text, writes: read().writes + 1, held: '' })
		}

		const tick = async (): Promise<void> => {
			if (busy) return
			busy = true
			try {
				const playback = sync()
				if (lyrics?.failed && Date.now() > retryAt) lyrics = null
				await load(playback.track)

				const now = Date.now()
				const setting = customStatusSetting()
				const decision = decide({
					settings,
					nowMs: now,
					playback,
					lyrics,
					written,
					writtenAtMs: writtenAt,
					holding: setting ? readText() : null,
					canWrite: !!setting,
				})
				patch({
					statusSetting: !!setting,
					track: playback.track
						? `${playback.track.name} — ${playback.track.artists}`
						: '',
					playing: playback.playing,
					positionMs: positionOf(playback, now),
					held: decision.kind === 'hold' ? decision.reason : '',
				})
				apply(decision)
			} catch (error) {
				note('error', message(error))
			} finally {
				busy = false
			}
		}

		cleanup(
			jsonStorage.subscribe(update => {
				const next = { ...settings, ...update }
				if (next.useUnsynced !== settings.useUnsynced) {
					lyrics = null
					wanted = ''
				}
				settings = next
				patch({ settings })
				void tick()
			}),
		)

		cleanup(watchSpotify(() => void tick()))
		const timer = setInterval(() => void tick(), TICK_MS)
		cleanup(() => clearInterval(timer))
		cleanup(() => {
			controller?.abort()
			forgetStatusSetting()
			resetDiagnostics()
		})

		void tick()
		return `LyricRPC ${VERSION}, writing at most every ${Math.round(cadenceMs(settings) / 1000)}s`
	},
})
