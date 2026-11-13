import { DEFAULT_SETTINGS } from './types'
import type { Settings } from './types'

/** What the running plugin knows, shown on the settings screen so a phone can be fixed by screenshot. */
export type Diagnostics = {
	running: boolean
	startedAt: number
	/** Whether Discord's status setting object was found. Nothing can be written without it. */
	statusSetting: boolean
	settings: Settings
	track: string
	playing: boolean
	positionMs: number
	lines: number
	synced: boolean
	source: string
	/** Why the last tick did not write. */
	held: string
	written: string
	writtenAt: number
	writes: number
	clears: string[]
	lastError: string
}

export const diagnostics: Diagnostics = {
	running: false,
	startedAt: 0,
	statusSetting: false,
	settings: DEFAULT_SETTINGS,
	track: '',
	playing: false,
	positionMs: 0,
	lines: 0,
	synced: false,
	source: '',
	held: 'not started',
	written: '',
	writtenAt: 0,
	writes: 0,
	clears: [],
	lastError: '',
}

let snapshot: Diagnostics = diagnostics
const listeners = new Set<() => void>()

export function patch(changes: Partial<Diagnostics>): void {
	snapshot = {
		...snapshot,
		...changes,
		clears: changes.clears ?? snapshot.clears,
	}
	for (const fn of listeners) fn()
}

export function read(): Diagnostics {
	return snapshot
}

export function subscribeDiagnostics(fn: () => void): () => void {
	listeners.add(fn)
	return () => listeners.delete(fn)
}

/** Keeps the last few refusals and errors, newest first, for the settings screen. */
export function note(kind: 'clear' | 'error', message: string): void {
	if (kind === 'error') {
		patch({ lastError: `${clock()}: ${message}` })
		return
	}
	patch({ clears: [`${clock()}: ${message}`, ...snapshot.clears].slice(0, 4) })
}

function clock(): string {
	const now = new Date()
	return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
}

export function resetDiagnostics(): void {
	patch({
		running: false,
		track: '',
		playing: false,
		positionMs: 0,
		lines: 0,
		source: '',
		held: 'stopped',
		written: '',
		writes: 0,
		clears: [],
		lastError: '',
	})
}
