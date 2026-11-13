/**
 * Reading and writing the custom status through Discord's own setting objects. These are the same
 * objects Discord's status screen uses, so a write lands where choosing a status by hand would put
 * it: saved, synced, and sent over the session the app is already on.
 */
import { lookupModule } from '@valenced/modules/finders'
import { withProps } from '@valenced/modules/finders/filters'

type ProtoSetting = {
	getSetting?: () => unknown
	updateSetting?: (value: unknown) => unknown
}

export type CustomStatus = {
	text: string
	emojiName: string
	emojiId: string
	expiresAtMs: string
	createdAtMs: string
}

let cached: ProtoSetting | undefined

/** `CustomStatusSetting`, found by its own name because which module carries it moves between builds. */
export function customStatusSetting(): ProtoSetting | null {
	if (cached) return cached
	try {
		const results = lookupStatusModule()
		if (results) cached = results
	} catch {
		// Discord has not loaded the module yet; it is looked for again on the next tick.
	}
	return cached || null
}

function lookupStatusModule(): ProtoSetting | null {
	for (const filter of [
		withProps('CustomStatusSetting', 'StatusSetting'),
		withProps('CustomStatusSetting'),
	]) {
		const found = lookupModule(filter)[0] as unknown as
			| Record<string, ProtoSetting | undefined>
			| undefined
		const holder = found?.CustomStatusSetting
			? found
			: (found?.default as Record<string, ProtoSetting | undefined> | undefined)
		const setting = holder?.CustomStatusSetting
		if (setting && typeof setting.getSetting === 'function') return setting
	}
	return null
}

export function forgetStatusSetting(): void {
	cached = undefined
}

/** The status exactly as Discord holds it, or null when none is set. */
export function readCustom(): CustomStatus | null {
	const setting = customStatusSetting()
	let raw: unknown
	try {
		raw = setting?.getSetting?.()
	} catch {
		return null
	}
	if (!raw || typeof raw !== 'object') return null
	const value = raw as Partial<Record<keyof CustomStatus, unknown>>
	if (!value.text && !value.emojiName) return null
	return {
		text: String(value.text || ''),
		emojiName: String(value.emojiName || ''),
		emojiId:
			value.emojiId && value.emojiId !== '0' ? String(value.emojiId) : '',
		expiresAtMs: String(value.expiresAtMs || '0'),
		createdAtMs: String(value.createdAtMs || '0'),
	}
}

/** The text of the status, or null when there is no status set. */
export function readText(): string | null {
	return readCustom()?.text ?? null
}

/**
 * Hands the status to Discord's setter. The save goes out in the background, so it is not awaited;
 * `onRefused` gets to hear about it when Discord says no.
 */
export function writeCustom(
	status: CustomStatus,
	onRefused?: (error: unknown) => void,
): boolean {
	const setting = customStatusSetting()
	if (!setting || typeof setting.updateSetting !== 'function') return false
	const result = setting.updateSetting(status) as
		| { catch?: (handler: (error: unknown) => void) => void }
		| undefined
	if (result && typeof result.catch === 'function') {
		result.catch(error => onRefused?.(error))
	}
	return true
}

export function clearCustom(onRefused?: (error: unknown) => void): boolean {
	return writeCustom(
		{
			text: '',
			emojiName: '',
			emojiId: '',
			expiresAtMs: '0',
			createdAtMs: String(Date.now()),
		},
		onRefused,
	)
}
