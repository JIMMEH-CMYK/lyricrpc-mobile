import { Design } from '@valenced/discord/design'
import { useEffect, useState } from 'react'
import { ScrollView } from 'react-native'
import { read, subscribeDiagnostics } from './state'
import {
	CASINGS,
	composeStatus,
	DECORATIONS,
	remaining,
	STATUS_LIMIT,
} from './style'
import { DEFAULT_SETTINGS } from './types'
import type { PluginSettingsComponent } from '@valenced/plugins/types'
import type { ComponentProps } from 'react'
import type { Diagnostics } from './state'
import type { Settings } from './types'

type Props = ComponentProps<PluginSettingsComponent<{ jsonStorage: Settings }>>

const PREVIEW = 'We are never ever getting back together'

const CADENCE_MIN = 15
const CADENCE_MAX = 120

/** Texts are expressions because React Native rejects raw text inside a component it cannot see is a Text. */
const CADENCE_HELP =
	'A new line only goes out this often. Discord counts every status change as a presence update, so it cannot follow the song word for word.'

function clock(ms: number): string {
	const total = Math.max(0, Math.round(ms / 1000))
	return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function ago(ms: number): string {
	const seconds = Math.round((Date.now() - ms) / 1000)
	if (seconds < 60) return `${seconds}s ago`
	return `${Math.floor(seconds / 60)}m ago`
}

/** Reads itself again whenever the running plugin has something new to say. */
function useDiagnostics(): Diagnostics {
	const [, redraw] = useState(0)
	useEffect(() => subscribeDiagnostics(() => redraw(count => count + 1)), [])
	return read()
}

export default function SettingsScreen({ api }: Props) {
	const settings = { ...DEFAULT_SETTINGS, ...api.jsonStorage.use() }
	const info = useDiagnostics()
	const save = (changes: Partial<Settings>): void => {
		void api.jsonStorage.set(changes)
	}

	const preview = composeStatus({ line: PREVIEW, settings })
	const cadenceNote = `Every ${settings.cadenceSec} seconds at most`
	const statusText = settings.enabled ? preview || 'nothing yet' : 'turned off'
	const detail = info.track
		? `${info.track} · ${info.playing ? 'playing' : 'paused'} ${clock(info.positionMs)}`
		: 'no Spotify track'

	return (
		<ScrollView
			style={{ flex: 1 }}
			contentContainerStyle={{ paddingBottom: 40 }}
		>
			<Design.Stack
				style={{ paddingVertical: 20, paddingHorizontal: 12 }}
				spacing={20}
			>
				<Design.TableRowGroup title="Right now" description={detail}>
					<Design.TableRow
						label={statusText}
						subLabel={`${remaining(preview)} of ${STATUS_LIMIT} characters free`}
					/>
					<Design.TableRow
						label={info.written ? `“${info.written}”` : 'Nothing written yet'}
						subLabel={
							info.written
								? `written ${ago(info.writtenAt)} · ${info.writes} write${info.writes === 1 ? '' : 's'} this session`
								: info.held || 'waiting for the first line'
						}
					/>
					{!info.running && (
						<Design.TableRow
							label="The plugin is not running"
							subLabel="Turn it on above, then reload Discord if you have just installed it."
						/>
					)}
					{info.running && !info.statusSetting && (
						<Design.TableRow
							label="Discord’s status setting was not found"
							subLabel="Nothing can be written until it loads. Open your profile status picker once, then come back here."
						/>
					)}
					{info.running && !!info.held && (
						<Design.TableRow label="Holding" subLabel={info.held} />
					)}
					{info.running && (
						<Design.TableRow
							label={`Lyric: ${info.source || 'none yet'}`}
							subLabel={
								info.lines
									? `${info.lines} lines${info.synced ? ', timed by LRCLIB' : ', spread by us'}`
									: 'no lines'
							}
						/>
					)}
					{info.lastError && (
						<Design.TableRow label="Last error" subLabel={info.lastError} />
					)}
					{info.clears.map(entry => (
						<Design.TableRow key={entry} label="Cleared" subLabel={entry} />
					))}
				</Design.TableRowGroup>

				<Design.TableRowGroup title="Lyrics">
					<Design.TableSwitchRow
						label="Show the current lyric line"
						subLabel="Follows whatever Spotify is playing on the account linked to Discord."
						value={settings.enabled}
						onValueChange={value => save({ enabled: value })}
					/>
					<Design.TableSwitchRow
						label="Never touch a status I set myself"
						subLabel="Only write when the status is empty or one this plugin wrote."
						value={settings.respectManual}
						onValueChange={value => save({ respectManual: value })}
					/>
					<Design.TableSwitchRow
						label="Clear the line when Spotify pauses"
						value={settings.clearOnPause}
						onValueChange={value => save({ clearOnPause: value })}
					/>
					<Design.TableSwitchRow
						label="Use words without timing"
						subLabel="When LRCLIB has the text but no timestamps, spread the lines evenly over the song."
						value={settings.useUnsynced}
						onValueChange={value => save({ useUnsynced: value })}
					/>
					<Design.Stack style={{ gap: 4 }} spacing={4}>
						<Design.Text variant="text-xs/bold" color="text-muted">
							{cadenceNote}
						</Design.Text>
						<Design.Text variant="text-xs/normal" color="text-muted">
							{CADENCE_HELP}
						</Design.Text>
					</Design.Stack>
					<Design.Slider
						step={5}
						minimumValue={CADENCE_MIN}
						maximumValue={CADENCE_MAX}
						value={Math.min(
							CADENCE_MAX,
							Math.max(CADENCE_MIN, settings.cadenceSec),
						)}
						onValueChange={value => save({ cadenceSec: Math.round(value) })}
					/>
				</Design.TableRowGroup>

				<Design.TableRowGroup title="How the line looks">
					<Design.TableRadioGroup
						title="Decoration"
						value={settings.decoration}
						onChange={decoration => save({ decoration })}
					>
						{DECORATIONS.map(entry => (
							<Design.TableRadioRow
								key={entry.id}
								label={entry.label}
								value={entry.id}
							/>
						))}
					</Design.TableRadioGroup>
					<Design.TableRadioGroup
						title="Lettering"
						value={settings.casing}
						onChange={casing => save({ casing })}
					>
						{CASINGS.map(entry => (
							<Design.TableRadioRow
								key={entry.id}
								label={entry.label}
								value={entry.id}
							/>
						))}
					</Design.TableRadioGroup>
					<Design.TextInput
						label="Before the line"
						value={settings.prefix}
						placeholder="♪ "
						onChange={prefix => save({ prefix })}
					/>
					<Design.TextInput
						label="After the line"
						value={settings.suffix}
						placeholder=" · Spotify"
						onChange={suffix => save({ suffix })}
					/>
					<Design.TextInput
						label="Status emoji"
						description="Goes in the status’s own emoji slot, so it costs none of the 128 characters."
						value={settings.emoji}
						placeholder="🎤"
						maxLength={8}
						onChange={emoji => save({ emoji })}
					/>
					<Design.TextInput
						label="Words to hide"
						description="Separate them with spaces. Each one comes out dotted."
						value={settings.censor}
						placeholder="never baby"
						onChange={censor => save({ censor })}
					/>
				</Design.TableRowGroup>
			</Design.Stack>
		</ScrollView>
	)
}
