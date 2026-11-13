import type { Casing, Decoration, Settings } from './types'

/**
 * Discord counts a custom status in UTF-16 code units, so a plain emoji or a bold letter costs two
 * of the 128. Length has to be measured in units, never in `.length`-of-codepoints.
 */
export const STATUS_LIMIT = 128

/** Cadence floor: every status change is a presence update, and Discord rate limits those. */
export const MIN_CADENCE_SEC = 15

export const DECORATIONS: {
	id: Decoration
	label: string
	open: string
	close: string
}[] = [
	{ id: 'none', label: 'Plain', open: '', close: '' },
	{ id: 'quote', label: '“Quotes”', open: '“', close: '”' },
	{ id: 'corner', label: '「Corners」', open: '「', close: '」' },
	{ id: 'bracket', label: '【Brackets】', open: '【', close: '】' },
	{ id: 'book', label: '《Books》', open: '《', close: '》' },
	{ id: 'heart', label: '♡ Hearts ♡', open: '♡ ', close: ' ♡' },
	{ id: 'note', label: '♪ Notes ♪', open: '♪ ', close: ' ♪' },
	{ id: 'sparkle', label: '✦ Sparkles ✦', open: '✦ ', close: ' ✦' },
	{ id: 'dash', label: '— Dashes —', open: '— ', close: ' —' },
]

export const CASINGS: { id: Casing; label: string }[] = [
	{ id: 'keep', label: 'Keep as written' },
	{ id: 'upper', label: 'UPPERCASE' },
	{ id: 'lower', label: 'lowercase' },
	{ id: 'title', label: 'Title Case' },
]

export function splitList(value: string): string[] {
	return value
		.split(/[\s,;]+/)
		.map(entry => entry.trim())
		.filter(Boolean)
}

export function decorationOf(id: Decoration): { open: string; close: string } {
	return DECORATIONS.find(entry => entry.id === id) || DECORATIONS[0]
}

export function applyCasing(text: string, casing: Casing): string {
	if (casing === 'upper') return text.toUpperCase()
	if (casing === 'lower') return text.toLowerCase()
	if (casing === 'title') {
		return text.replace(
			/\S+/g,
			word => word.slice(0, 1).toUpperCase() + word.slice(1).toLowerCase(),
		)
	}
	return text
}

/** Keeps the first character and replaces the rest of every hit with dots. */
export function censorWords(text: string, words: string[]): string {
	let out = text
	for (const word of words) {
		const needle = word.toLowerCase()
		if (!needle) continue
		const haystack = out.toLowerCase()
		let from = 0
		for (;;) {
			const at = haystack.indexOf(needle, from)
			if (at < 0) break
			const masked = `${out[at] || ''}${'•'.repeat(Math.max(0, needle.length - 1))}`
			out = out.slice(0, at) + masked + out.slice(at + needle.length)
			from = at + masked.length
		}
	}
	return out
}

/** Code units used by a string, which is what Discord charges for. */
export function utf16Length(text: string): number {
	return text.length
}

/** Trims to a number of UTF-16 units without ever cutting a surrogate pair in half. */
export function trimToUnits(text: string, limit: number): string {
	if (text.length <= limit) return text
	let taken = ''
	for (const point of text) {
		if (taken.length + point.length > limit) break
		taken += point
	}
	return taken
}

export type StatusParts = {
	line: string
	settings: Pick<
		Settings,
		'prefix' | 'suffix' | 'decoration' | 'casing' | 'censor'
	>
}

/** The whole styled status, already inside Discord's 128 code unit budget. */
export function composeStatus({ line, settings }: StatusParts): string {
	const words = splitList(settings.censor)
	const body = censorWords(
		applyCasing(line.trim(), settings.casing),
		words,
	).trim()
	if (!body) return ''
	const { open, close } = decorationOf(settings.decoration)
	const text = `${settings.prefix}${open}${body}${close}${settings.suffix}`
	return trimToUnits(text, STATUS_LIMIT).trimEnd()
}

/** How many code units are still free, so the settings screen can show it. */
export function remaining(text: string): number {
	return STATUS_LIMIT - text.length
}
