/**
 * Stitches the per-test recordings into one watchable mp4 at the repo root.
 *
 * <p><b>Why a build step at all.</b> Playwright emits one .webm per test, named after a truncated
 * hash of the test title, in a directory nobody browses. That is an artifact; a single ordered mp4
 * with a title card before each journey is something a person can actually watch.
 *
 * <p><b>Why mp4.</b> webm does not play in Windows' default video player, and the deliverable is
 * meant to be opened by whoever is on support — not by someone who first installs a codec.
 *
 * <p><b>ffmpeg comes from `ffmpeg-static`, not from Playwright.</b> The obvious move is to reuse
 * the ffmpeg Playwright already ships — but that build is cut down to exactly what recording
 * needs: it encodes VP8 only, and has neither libx264 nor drawtext nor the lavfi input used to
 * synthesize title cards. Verified by asking it directly rather than by assuming. So a real build
 * is a dev dependency of this suite; it is never shipped with the product.
 *
 * <p>Usage: node build-video.mjs
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')
const ARTIFACTS = join(HERE, 'artifacts')
const WORK = join(HERE, '.video-build')

// VIDEO_OUT lets a caller place the recording alongside the notes it belongs to — the brand-owner
// journey writes into brands/<date>/ so the video and its write-up stay together. Default is
// unchanged so the existing suite still lands at the repo root.
const OUTPUT = process.env.VIDEO_OUT || join(REPO_ROOT, 'influencrm-e2e-journeys.mp4')

/** 720p, matching the recording size — rescaling would only soften the text. */
const WIDTH = 1280
const HEIGHT = 720
const TITLE_SECONDS = 2.5

function findFfmpeg() {
  // ffmpeg-static resolves to a full build with libx264 and drawtext. Deliberately NOT
  // Playwright's bundled ffmpeg — see the note at the top of this file.
  const staticPath = join(HERE, 'node_modules', 'ffmpeg-static', 'ffmpeg.exe');
  if (existsSync(staticPath)) return staticPath

  const staticUnix = join(HERE, 'node_modules', 'ffmpeg-static', 'ffmpeg')
  if (existsSync(staticUnix)) return staticUnix

  // A system ffmpeg is the last resort; if it is also feature-poor, the run fails loudly with
  // ffmpeg's own message rather than producing a video missing its title cards.
  return 'ffmpeg'
}

const FFMPEG = findFfmpeg()

function run(args) {
  try {
    execFileSync(FFMPEG, args, { stdio: ['ignore', 'ignore', 'pipe'], encoding: 'utf8' })
  } catch (failure) {
    // ffmpeg puts the actual reason on stderr; the default Error message is just an exit code,
    // and a Buffer dump of stderr is unreadable. Surface the last lines, which carry the cause.
    const detail = String(failure.stderr || '').trim().split(/\r?\n/).slice(-6).join(' | ')
    throw new Error(`ffmpeg failed: ${detail}`)
  }
}

/**
 * Recovers a readable journey name from Playwright's directory name.
 *
 * <p>Those names are truncated and hash-infixed, so they are lossy by design. results.json holds
 * the real titles, which is why it is preferred and this is only the fallback.
 */
function prettifyDirName(name) {
  return name
    .replace(/-chromium$/, '')
    .replace(/^journeys-/, '')
    .replace(/-[0-9a-f]{5}-/, ' — ')
    .replace(/-/g, ' ')
    .trim()
}

/** Maps each recording to its real test title, in the order the suite ran. */
function collectClips() {
  const clips = []
  const resultsPath = join(HERE, 'results.json')

  if (existsSync(resultsPath)) {
    const report = JSON.parse(readFileSync(resultsPath, 'utf8'))
    const walk = (suite, trail) => {
      for (const child of suite.suites || []) {
        walk(child, [...trail, child.title])
      }
      for (const spec of suite.specs || []) {
        for (const testCase of spec.tests || []) {
          for (const result of testCase.results || []) {
            for (const attachment of result.attachments || []) {
              if (attachment.name === 'video' && attachment.path && existsSync(attachment.path)) {
                clips.push({
                  // The describe block is the persona; the spec is the job. Both belong on screen.
                  group: trail.filter(Boolean).slice(-1)[0] || '',
                  title: spec.title,
                  status: result.status,
                  path: attachment.path,
                })
              }
            }
          }
        }
      }
    }
    for (const suite of report.suites || []) {
      walk(suite, [suite.title])
    }
  }

  if (clips.length === 0) {
    // No usable report — fall back to whatever recordings are on disk so the video is still
    // produced. VIDEO_TITLE / VIDEO_GROUP override the lossy directory name, which is truncated
    // and hash-infixed and reads as garbage on a title card.
    for (const dir of readdirSync(ARTIFACTS)) {
      const video = join(ARTIFACTS, dir, 'video.webm')
      if (existsSync(video)) {
        clips.push({
          group: process.env.VIDEO_GROUP || '',
          title: process.env.VIDEO_TITLE || prettifyDirName(dir),
          status: process.env.VIDEO_STATUS || 'unknown',
          path: video,
        })
      }
    }
  }
  return clips
}

/** ffmpeg's drawtext parser treats these as syntax, so they must be escaped, not stripped. */
function escapeText(value) {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/:/g, '\\:')
    .replace(/'/g, '')
    .replace(/%/g, '')
    // Non-ASCII (the em dashes in our test titles) renders as tofu in the default font.
    .replace(/[^\x20-\x7E]/g, '-')
}

function buildTitleCard(index, clip, outPath) {
  const group = escapeText(clip.group || 'Journey')
  const title = escapeText(clip.title)
  const badge = clip.status === 'passed' ? 'PASS' : clip.status.toUpperCase()

  const draw = [
    `drawtext=text='${group}':fontcolor=0x8AB4F8:fontsize=34:x=(w-tw)/2:y=h/2-90`,
    `drawtext=text='${title}':fontcolor=white:fontsize=44:x=(w-tw)/2:y=h/2-20`,
    `drawtext=text='${badge}':fontcolor=0x81C995:fontsize=30:x=(w-tw)/2:y=h/2+70`,
  ].join(',')

  run([
    '-y', '-f', 'lavfi',
    '-i', `color=c=0x0F1115:s=${WIDTH}x${HEIGHT}:d=${TITLE_SECONDS}:r=25`,
    '-vf', draw,
    '-pix_fmt', 'yuv420p', '-c:v', 'libx264', '-preset', 'veryfast',
    outPath,
  ])
}

function transcode(source, outPath) {
  run([
    '-y', '-i', source,
    // Recordings can vary by a pixel; forcing the exact size keeps concat from refusing them.
    '-vf', `scale=${WIDTH}:${HEIGHT}:force_original_aspect_ratio=decrease,`
      + `pad=${WIDTH}:${HEIGHT}:(ow-iw)/2:(oh-ih)/2:color=0x0F1115,fps=25,setsar=1`,
    '-pix_fmt', 'yuv420p', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '26',
    '-an',
    outPath,
  ])
}

function main() {
  if (!existsSync(ARTIFACTS)) {
    console.error(`No recordings at ${ARTIFACTS}. Run: npx playwright test`)
    process.exit(1)
  }

  const clips = collectClips()
  if (clips.length === 0) {
    console.error('No videos found. Was the suite run with video enabled?')
    process.exit(1)
  }

  rmSync(WORK, { recursive: true, force: true })
  mkdirSync(WORK, { recursive: true })
  // VIDEO_OUT may point somewhere that does not exist yet (a dated journey folder, say).
  mkdirSync(dirname(OUTPUT), { recursive: true })

  const segments = []
  clips.forEach((clip, index) => {
    const card = join(WORK, `card-${String(index).padStart(2, '0')}.mp4`)
    const body = join(WORK, `body-${String(index).padStart(2, '0')}.mp4`)
    buildTitleCard(index, clip, card)
    transcode(clip.path, body)
    segments.push(card, body)
    console.log(`  [${index + 1}/${clips.length}] ${clip.group} - ${clip.title}`)
  })

  // The concat demuxer needs forward slashes even on Windows.
  const listFile = join(WORK, 'segments.txt')
  writeFileSync(listFile, segments.map((s) => `file '${s.replace(/\\/g, '/')}'`).join('\n'))

  run(['-y', '-f', 'concat', '-safe', '0', '-i', listFile, '-c', 'copy', OUTPUT])
  rmSync(WORK, { recursive: true, force: true })

  console.log(`\nWrote ${OUTPUT}`)
}

main()
