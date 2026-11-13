import type { Playback } from './types'

export const EMPTY_PLAYBACK: Playback = {
	track: null,
	playing: false,
	positionMs: 0,
	atMs: 0,
}

function text(value: unknown): string {
	return typeof value === 'string' ? value.trim() : ''
}

function number(value: unknown): number | undefined {
	const parsed = typeof value === 'string' ? Number(value) : value
	return typeof parsed === 'number' && Number.isFinite(parsed)
		? parsed
		: undefined
}

function first(...values: unknown[]): number | undefined {
	for (const value of values) {
		const parsed = number(value)
		if (parsed !== undefined) return parsed
	}
	return undefined
}

function called(source: any, name: string): unknown {
	try {
		return typeof source?.[name] === 'function' ? source[name]() : undefined
	} catch {
		return undefined
	}
}

/**
 * Reads a track out of either shape it arrives in: the store keeps the song on `item` and answers
 * for the clock through `getSyncedPosition()`, while the `SPOTIFY_PLAYER_STATE` payload keeps it on
 * `track`, carries the numbers directly and means "nothing is playing" with a null one.
 *
 * `previous` matters for the clock. A position that came from a getter is live, so it is stamped
 * now. A position read off a plain field was only ever true for the moment it was dispatched, so
 * re-reading it on the same song must keep the older stamp or the song looks frozen where it was.
 */
export function readPlayback(source: any, previous?: Playback): Playback {
	if (!source || typeof source !== 'object') return EMPTY_PLAYBACK
	const item = source.item ?? source.track
	if (item === null) return EMPTY_PLAYBACK
	const holder = item && typeof item === 'object' ? item : source

	const name = text(holder.name) || text(holder.title)
	if (!name) return EMPTY_PLAYBACK

	const artists =
		(Array.isArray(holder.artists) ? holder.artists : [])
			.map((artist: any) => text(artist?.name))
			.filter(Boolean)
			.join(', ') || text(holder.artist)

	const album =
		text(holder.album?.name) || text(holder.album_name) || text(holder.album)
	const durationMs =
		first(
			called(source, 'getSyncedDuration'),
			called(source, 'getDuration'),
			source.duration_ms,
			holder.duration,
			source.duration,
		) ?? 0
	const livePosition = first(
		called(source, 'getSyncedPosition'),
		called(source, 'getPosition'),
	)
	const positionMs =
		livePosition ??
		first(source.position, source.progress, source.progress_ms) ??
		0
	const playingMethod = called(source, 'isPlaying')
	const activeMethod = called(source, 'isActive')
	const playing =
		typeof playingMethod === 'boolean'
			? playingMethod
			: typeof source.playing === 'boolean'
				? source.playing
				: typeof source.paused === 'boolean'
					? !source.paused
					: typeof source.is_playing === 'boolean'
						? source.is_playing
						: typeof activeMethod === 'boolean'
							? activeMethod
							: typeof source.active === 'boolean'
								? source.active
								: Boolean(source.device?.is_active)

	const id =
		text(holder.id) || text(holder.track_id) || text(holder.uri) || name
	const key = `${id}|${artists}`
	const sameSong = previous?.track?.key === key
	return {
		track: { key, name, artists, album, durationMs: Math.max(0, durationMs) },
		playing,
		positionMs: Math.max(0, positionMs),
		atMs: livePosition === undefined && sameSong ? previous.atMs : Date.now(),
	}
}
