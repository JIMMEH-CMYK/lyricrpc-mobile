/**
 * Packs each JS-only plugin into the artifact the repository pool expects:
 * `build/dist/<id>@<version>.zip` holding `manifest.json` and the built bundle at its root.
 *
 *     bun run package
 */

import {
	existsSync,
	mkdirSync,
	readdirSync,
	readFileSync,
	writeFileSync,
} from 'node:fs'
import { join } from 'node:path'
import { zipSync } from 'fflate'

const root = join(import.meta.dir, '..')
const dist = join(root, 'build', 'dist')

/** Fixed so a rebuild of the same source gives the same sha256 in the index. */
const mtime = new Date(1980, 0, 1)

function packagePlugin(dir: string): string {
	const manifestPath = join(dir, 'manifest.json')
	const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))

	if (manifest.dist?.android)
		throw new Error(
			`${dir}: native plugins need Gradle, this repo packs JS only`,
		)
	if (existsSync(join(dir, 'assets')))
		throw new Error(`${dir}: assets are not packed, add them to the zip first`)

	const script = manifest.dist?.script
	if (!script) throw new Error(`${dir}: manifest has no dist.script`)
	const bundle = join(dir, 'build', 'js', script)
	if (!existsSync(bundle))
		throw new Error(`${bundle}: missing, run bun run build`)

	const zip = zipSync(
		{
			'manifest.json': readFileSync(manifestPath),
			[script]: readFileSync(bundle),
		},
		{ mtime },
	)

	mkdirSync(dist, { recursive: true })
	const file = join(dist, `${manifest.id}@${manifest.version}.zip`)
	writeFileSync(file, zip)
	return file
}

for (const kind of ['plugins', 'themes']) {
	const base = join(root, kind)
	if (!existsSync(base)) continue
	for (const name of readdirSync(base).sort()) {
		const dir = join(base, name)
		if (existsSync(join(dir, 'manifest.json'))) console.log(packagePlugin(dir))
	}
}
