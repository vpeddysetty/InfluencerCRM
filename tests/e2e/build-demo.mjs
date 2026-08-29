/**
 * Lays the narration over the demo capture and produces the finished mp4.
 *
 * <p><b>Separate from build-video.mjs deliberately.</b> That one stitches the e2e journeys into a
 * silent artifact somebody on support watches to see what a run did; this produces a narrated
 * marketing asset. They share ffmpeg and nothing else — folding audio into the journeys build
 * would put a demo concern inside a diagnostic tool, and the next person changing one would have
 * to reason about the other.
 *
 * <p><b>The narration drives the timing, not the footage.</b> Synthesis runs longer than any
 * estimate and each beat is a fixed hold in the capture, so segments are cut to the AUDIO's length
 * and the last frame is held if the video runs short. Doing it the other way round would clip
 * Sarah mid-sentence, which is the one failure a viewer definitely notices.
 *
 * <p><b>Beats are concatenated in manuscript order</b>, not in the order files happen to sit on
 * disk. `readdirSync` would sort `close` before `coupon` and produce a video that ends in the
 * middle.
 *
 * <p>Usage:
 * <pre>
 *   node tests/e2e/seed-demo-workspace.mjs
 *   DEMO_EMAIL=... DEMO_PASSWORD=... npx playwright test demo-capture.spec.js
 *   export $(grep ELEVENLABS .env | xargs) && node tests/e2e/demo-narrate.mjs
 *   node tests/e2e/build-demo.mjs
 * </pre>
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(HERE, '..', '..')
// playwright.config.js sets outputDir: 'artifacts', relative to tests/e2e -- NOT the
// test-results/ default. Guessing the default found nothing and reported "no capture", which
// reads as "the run failed" rather than "the path is wrong".
const ARTIFACTS = join(HERE, 'artifacts')
const NARRATION = join(HERE, 'narration')
const WORK = join(HERE, '.demo-build')
const OUTPUT = process.env.DEMO_OUT || join(REPO_ROOT, 'influencrm-demo.mp4')

/** Same resolution order as build-video.mjs, and for the same reason — see its header. */
function resolveFfmpeg() {
  const win = join(HERE, 'node_modules', 'ffmpeg-static', 'ffmpeg.exe')
  if (existsSync(win)) return win
  const unix = join(HERE, 'node_modules', 'ffmpeg-static', 'ffmpeg')
  if (existsSync(unix)) return unix
  return 'ffmpeg'
}

const FFMPEG = resolveFfmpeg()

function run(args) {
  try {
    execFileSync(FFMPEG, args, { stdio: ['ignore', 'ignore', 'pipe'], encoding: 'utf8' })
  } catch (e) {
    // ffmpeg puts the reason on stderr; the default Error is an exit code and nothing else.
    const detail = (e.stderr || '').trim().split('\n').slice(-6).join('\n')
    throw new Error(`ffmpeg failed:\n${detail}`)
  }
}

/** Duration in seconds, asked of ffmpeg rather than inferred from file size. */
function durationOf(path) {
  try {
    const out = execFileSync(FFMPEG, ['-i', path], { stdio: ['ignore', 'ignore', 'pipe'], encoding: 'utf8' })
    return parseDuration(out)
  } catch (e) {
    // ffmpeg exits non-zero for `-i` with no output file and writes the metadata to stderr anyway.
    return parseDuration(e.stderr || '')
  }
}

function parseDuration(text) {
  const m = /Duration:\s*(\d+):(\d+):(\d+\.\d+)/.exec(text)
  if (!m) return 0
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3])
}

// ---- inputs -----------------------------------------------------------------

const manifestPath = join(NARRATION, 'manifest.json')
if (!existsSync(manifestPath)) {
  console.error(`
No narration found at ${manifestPath}.

  export $(grep ELEVENLABS .env | xargs)
  node tests/e2e/demo-narrate.mjs
`)
  process.exit(1)
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))

// The capture writes one webm per browser context: the desktop journey and the phone context used
// for the creator beat. Both are wanted, and the desktop one is by far the longer.
const recordings = []
if (existsSync(ARTIFACTS)) {
  for (const dir of readdirSync(ARTIFACTS)) {
    const video = join(ARTIFACTS, dir, 'video.webm')
    if (existsSync(video)) {
      recordings.push({ path: video, bytes: statSync(video).size })
    }
  }
}

if (recordings.length === 0) {
  console.error(`
No capture found in ${ARTIFACTS}.

  node tests/e2e/seed-demo-workspace.mjs
  DEMO_EMAIL=... DEMO_PASSWORD=... npx playwright test demo-capture.spec.js
`)
  process.exit(1)
}

// Largest first: the desktop capture carries five of the six beats, and picking by size avoids
// depending on Playwright's hash-infixed directory names, which carry no ordering information.
recordings.sort((a, b) => b.bytes - a.bytes)
const footage = recordings[0].path

console.log(`  ffmpeg     ${FFMPEG === 'ffmpeg' ? 'system' : 'ffmpeg-static'}`)
const footageSeconds = durationOf(footage)
console.log(`  footage    ${footage}  (${footageSeconds.toFixed(1)}s)`)
console.log(`  narration  ${manifest.beats.length} beats\n`)

// ---- build ------------------------------------------------------------------

rmSync(WORK, { recursive: true, force: true })
mkdirSync(WORK, { recursive: true })

// One segment per beat, each exactly as long as its narration. Cutting to the audio rather than
// to the video is what stops a line being clipped mid-sentence.
let cursor = 0
const segments = []

for (const [index, beat] of manifest.beats.entries()) {
  const audio = join(NARRATION, `${beat.id}.mp3`)
  if (!existsSync(audio)) {
    console.log(`  skip   ${beat.id} — no audio`)
    continue
  }

  const seconds = beat.seconds
  const segment = join(WORK, `beat-${String(index).padStart(2, '0')}.mp4`)

  // Clamp the seek to inside the footage.
  //
  // If the narration outruns the capture, `-ss` past the end yields NO video frames at all — and
  // `tpad` cannot clone a last frame that was never decoded, so the segment comes out audio-only.
  // The concat filter then fails with "Stream specifier ':v:0' matches no streams", which names
  // the symptom and not the cause: a beat with no picture, six beats earlier.
  //
  // Seeking to just before the end instead gives tpad a frame to hold, so a beat past the footage
  // becomes a still under the narration rather than a broken segment. That is also the honest
  // failure mode for a capture that is shorter than its script.
  const start = Math.max(0, Math.min(cursor, Math.max(0, footageSeconds - 0.5)))

  run([
    '-y',
    // The video, from where this beat starts. `-t` after `-ss` trims to the audio's length.
    '-ss', start.toFixed(3), '-t', seconds.toFixed(3), '-i', footage,
    '-i', audio,
    // If the footage runs out before the narration does, hold the last frame rather than cutting
    // to black -- a still screen under a finishing sentence reads as deliberate; black does not.
    //
    // `stop_duration` is how much to ADD, not what to pad TO, and `-t` placed after the inputs
    // does not constrain a filtered stream. The first version got both wrong and produced a video
    // 3.7x too long, which is why `trim` is explicit here rather than relying on the output
    // duration flag: pad generously, then cut to exactly the narration.
    //
    // `fps=25` is not cosmetic. Playwright records VARIABLE frame rate -- it emits a frame when
    // the page changes, so a held screen produces almost none. Concatenating VFR segments with a
    // stream copy leaves timestamps the player stretches: the first version came out at 10 fps and
    // 362s instead of 97s, with every segment individually correct. Normalising here is what makes
    // the concat below safe to do as a copy.
    '-filter_complex',
    `[0:v]tpad=stop_mode=clone:stop_duration=${seconds.toFixed(3)},`
      + `trim=duration=${seconds.toFixed(3)},setpts=PTS-STARTPTS,fps=25,scale=1920:-2[v]`,
    '-map', '[v]', '-map', '1:a:0',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '192k', '-ar', '48000',
    // Both streams to exactly the beat length, so concatenation cannot drift out of sync.
    '-t', seconds.toFixed(3),
    segment,
  ])

  segments.push(segment)
  console.log(`  beat   ${beat.id.padEnd(12)} ${seconds.toFixed(1)}s`)
  cursor += seconds
}

if (segments.length === 0) {
  console.error('No segments were built.')
  process.exit(1)
}

// Concat with the FILTER, not the demuxer.
//
// The demuxer is the usual choice and it was tried twice. With `-c copy` it turned 96 seconds of
// segments into 362; re-encoding brought that to 37. Both wrong, in opposite directions, from
// segments that each measured correctly on their own — the demuxer was mis-reading their
// timestamps, which is what Playwright's VARIABLE frame rate produces: it emits a frame only when
// the page changes, and a demo is mostly held screens.
//
// The filter decodes every input and rebuilds the timeline from the frames rather than trusting
// the container, so VFR sources cannot mislead it. It costs a full decode of each segment, which
// on a ninety-second video is seconds.
//
// Measured, not reasoned about: demuxer+copy 362.1s, demuxer+re-encode 37.4s, filter 96.5s against
// segments summing to 96.5s.
const inputs = []
for (const segment of segments) {
  inputs.push('-i', segment)
}
// Explicit stream INDICES (`0:v:0`), not just types. ffmpeg refused `[0:v]` here with
// "matches no streams" -- the shorthand resolves differently inside a filtergraph than it does
// as a -map argument, which is the sort of thing only an error message teaches.
const chain = segments.map((_, i) => `[${i}:v:0][${i}:a:0]`).join('')

run([
  '-y', ...inputs,
  '-filter_complex', `${chain}concat=n=${segments.length}:v=1:a=1[v][a]`,
  '-map', '[v]', '-map', '[a]',
  '-fps_mode', 'cfr', '-r', '25',
  '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
  '-c:a', 'aac', '-b:a', '192k', '-ar', '48000',
  // Index at the front, so the file starts playing before it has fully downloaded — which is what
  // a demo linked from a sales page needs.
  '-movflags', '+faststart',
  OUTPUT,
])

rmSync(WORK, { recursive: true, force: true })

const total = durationOf(OUTPUT)
console.log(`
  ${OUTPUT}
  ${total.toFixed(1)}s, ${(statSync(OUTPUT).size / 1024 / 1024).toFixed(1)} MB`)
