/**
 * The Spotify session Discord is already tracking, which is the same session its own activity card
 * reads. Three doors report it and which one fires depends on how playback started, so all three are
 * watched into one shared state.
 */
import { getStore, onFluxEventDispatched } from '@valenced/discord/flux'
import { AppState } from 'react-native'
import { EMPTY_PLAYBACK, readPlayback } from './playback'
import type { Playback } from './types'

let store: any = null
let playback: Playback = EMPTY_PLAYBACK
/** One empty store read is Discord mid-update, not the song ending. */
let emptyReads = 0

/** Reads the store into the shared state and hands back where the song is now. */
export function sync(): Playback {
	if (store) {
		const next = readPlayback(store, playback)
		if (next.track) {
			emptyReads = 0
			playback = next
		} else if (playback.track && ++emptyReads >= 2) {
			emptyReads = 0
			playback = EMPTY_PLAYBACK
		}
	}
	return playback
}

function adopt(next: Playback): void {
	emptyReads = 0
	playback = next.track ? next : EMPTY_PLAYBACK
}

const moved = (previous: Playback, next: Playback): boolean =>
	previous.track?.key !== next.track?.key || previous.playing !== next.playing

/**
 * @param onChange fires when the track or the playing flag changes.
 */
export function watchSpotify(
	onChange: (playback: Playback) => void,
): () => void {
	const undo: (() => void)[] = []

	undo.push(
		getStore('SpotifyStore', value => {
			store = value
			const push = (): void => {
				const previous = playback
				if (moved(previous, sync())) onChange(playback)
			}
			try {
				store?.addChangeListener?.(push)
				undo.push(() => store?.removeChangeListener?.(push))
			} catch {
				// Without the change listener the flux events below still carry the state.
			}
			push()
		}),
	)

	undo.push(
		onFluxEventDispatched('SPOTIFY_PLAYER_STATE', event => {
			const previous = playback
			adopt(readPlayback(event))
			if (moved(previous, playback)) onChange(playback)
			return event
		}),
	)

	undo.push(
		onFluxEventDispatched('LOGOUT', event => {
			const previous = playback
			adopt(EMPTY_PLAYBACK)
			if (moved(previous, playback)) onChange(playback)
			return event
		}),
	)

	const foreground = AppState.addEventListener('change', state => {
		if (state !== 'active') return
		const previous = playback
		if (moved(previous, sync())) onChange(playback)
	})
	undo.push(() => foreground.remove())

	return () => {
		for (const fn of undo.reverse()) fn()
		store = null
		emptyReads = 0
		playback = EMPTY_PLAYBACK
	}
}
