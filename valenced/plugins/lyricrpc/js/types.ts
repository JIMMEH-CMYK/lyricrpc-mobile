/** One timed line of a synced lyric. */
export type LyricLine = {
	/** Milliseconds from the start of the track. */
	t: number
	text: string
}

/** What Discord's Spotify session says is playing. */
export type Track = {
	/** Stable id for this recording: used as the lyric cache key. */
	key: string
	name: string
	artists: string
	album: string
	durationMs: number
}

/** A track plus where it is and whether it is moving. */
export type Playback = {
	track: Track | null
	playing: boolean
	/** Position reported for the track, valid at `atMs`. */
	positionMs: number
	atMs: number
}

/** The lyrics found for one track. */
export type LyricSet = {
	key: string
	lines: LyricLine[]
	/** Whether these lines are timed by LRCLIB rather than spread by us. */
	synced: boolean
	/** Set when the lookup failed, so a tick can say why there is nothing to show. */
	failed?: string
	/** Where the lines came from, for the diagnostics readout. */
	source: string
}

export type Casing = 'keep' | 'upper' | 'lower' | 'title'

export type Decoration =
	| 'none'
	| 'quote'
	| 'corner'
	| 'bracket'
	| 'book'
	| 'heart'
	| 'note'
	| 'sparkle'
	| 'dash'

export type Settings = {
	enabled: boolean
	/** Shortest gap between two status writes, in seconds. */
	cadenceSec: number
	decoration: Decoration
	casing: Casing
	prefix: string
	suffix: string
	/** Plain emoji written into the status's own emoji slot. */
	emoji: string
	/** Words to mask out of the lyric, space or newline separated. */
	censor: string
	/** Leave a status alone when it was not written by this plugin. */
	respectManual: boolean
	/** Clear the status when Spotify pauses instead of keeping the last line. */
	clearOnPause: boolean
	/** Fall back to unsynced lyrics, spread evenly over the track. */
	useUnsynced: boolean
}

export const DEFAULT_SETTINGS: Settings = {
	enabled: true,
	cadenceSec: 20,
	decoration: 'quote',
	casing: 'keep',
	prefix: '',
	suffix: '',
	emoji: '',
	censor: '',
	respectManual: true,
	clearOnPause: true,
	useUnsynced: true,
}
