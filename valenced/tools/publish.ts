/**
 * Publishes the repository the way the pool layout expects: every artifact in `build/dist` is
 * copied into `published/pool` on the pool branch, `index.json` is regenerated from that directory
 * alone, and the two go out in one commit so the index never points at a file that is not there.
 *
 *     bun run package && bun run publish [-- --branch gh-pages] [--dry-run]
 */

import { execFileSync } from 'node:child_process'
import {
	copyFileSync,
	existsSync,
	mkdirSync,
	readdirSync,
	rmSync,
} from 'node:fs'
import { basename, join } from 'node:path'

const root = join(import.meta.dir, '..')
const published = join(root, 'published')
const argv = process.argv.slice(2)
const dryRun = argv.includes('--dry-run')
const flag = (name: string, otherwise: string): string => {
	const value = argv.find(arg => arg.startsWith(`${name}=`))
	if (value) return value.slice(name.length + 1)
	const index = argv.indexOf(name)
	return index >= 0 ? (argv[index + 1] ?? otherwise) : otherwise
}
const branch = flag('--branch', 'gh-pages')

function run(cwd: string, ...command: string[]): string {
	return execFileSync(command[0]!, command.slice(1), {
		cwd,
		encoding: 'utf8',
		stdio: ['ignore', 'pipe', 'inherit'],
	}).trim()
}

function git(args: string[]): string {
	return run(published, 'git', ...args)
}

/** The pool clone is freshly made and has no identity of its own, so commit as the repository owner. */
function identity(): string[] {
	const configured = (key: string): string => {
		try {
			return run(root, 'git', 'config', '--get', key)
		} catch {
			return ''
		}
	}
	const name = configured('user.name')
	const email = configured('user.email')
	return [
		'-c',
		`user.name=${name || owner}`,
		'-c',
		`user.email=${email || `${owner}@users.noreply.github.com`}`,
	]
}

const origin = run(root, 'git', 'remote', 'get-url', 'origin')
	.replace(/^git@github.com:/, 'https://github.com/')
	.replace(/\.git$/, '')
const [, owner = '', repo = ''] =
	/github\.com[/:]([^/]+)\/([^/?]+)/.exec(origin) ?? []
if (!owner || !repo)
	throw new Error(`${origin}: not a GitHub remote, cannot name the Pages URL`)

// GitHub Pages lowercases the owner, so the index matches what the site is actually served as.
const base = `https://${owner.toLowerCase()}.github.io/${repo}`
const dist = join(root, 'build', 'dist')
const artifacts = existsSync(dist)
	? readdirSync(dist).filter(name => name.endsWith('.zip'))
	: []
if (artifacts.length === 0)
	throw new Error(`${dist} is empty, run bun run build && bun run package`)

if (existsSync(published)) rmSync(published, { recursive: true, force: true })
if (run(root, 'git', 'ls-remote', '--heads', origin, branch))
	run(
		root,
		'git',
		'clone',
		'--quiet',
		'--depth',
		'1',
		'--branch',
		branch,
		origin,
		published,
	)
else {
	// No pool branch yet, so the first publish starts one from nothing.
	mkdirSync(published, { recursive: true })
	run(published, 'git', 'init', '--quiet', '--initial-branch', branch)
	run(published, 'git', 'remote', 'add', 'origin', origin)
}

mkdirSync(join(published, 'pool'), { recursive: true })
for (const artifact of artifacts)
	copyFileSync(join(dist, artifact), join(published, 'pool', artifact))

run(
	root,
	process.execPath,
	'x',
	'valenced-plugin',
	'generate-index',
	'--dist',
	join(published, 'pool'),
	'--base-url',
	`${base}/pool`,
	'--out',
	join(published, 'index.json'),
)

git(['add', '--all', 'pool', 'index.json'])
const staged = git(['diff', '--cached', '--name-only'])
const served = `${base}/index.json`
if (!staged)
	console.log(`Nothing to publish; ${served} already matches the pool.`)
else if (dryRun)
	console.log(`Dry run. Would commit and push:\n${staged}\n${served}`)
else {
	const names = artifacts.map(name => basename(name, '.zip'))
	git([...identity(), 'commit', '--quiet', '-m', `Publish ${names.join(', ')}`])
	git(['push', '--quiet', 'origin', branch])
	console.log(
		`Published ${artifacts.length} artifact(s). Repository URL: ${served}`,
	)
}
