/**
 * Synthesises Sarah's narration, one file per beat (docs/Demo-Script-Collaborative-Drop.md).
 *
 * <p><b>The key comes from the environment and is never written anywhere.</b> Not to a log, not
 * into a filename, not into the manifest this produces. An ElevenLabs key is a billable credential
 * and the usual way one escapes is a debug line somebody left in.
 *
 * <p><b>One file per beat, not one long take.</b> A single render means re-synthesising ninety
 * seconds to fix one sentence, and the beats are also the unit the capture holds for — so a line
 * that runs long is repaired by editing that beat and re-running just it. Existing files are
 * skipped for the same reason: iterating on beat three should not re-bill the other six.
 *
 * <p><b>It reports measured durations against the script's targets.</b> Synthesis nearly always
 * runs longer than an estimate, and the capture holds fixed seconds per beat — so a beat that
 * overruns is something to know before the footage is cut, not after. The mismatch is printed
 * rather than silently corrected, because the fix is a judgement call: trim the words, or lengthen
 * the hold in demo-capture.spec.js.
 *
 * <p>Usage:
 * <pre>
 *   export $(grep ELEVENLABS .env | xargs)
 *   node tests/e2e/demo-narrate.mjs            # only missing beats
 *   node tests/e2e/demo-narrate.mjs --force    # re-render everything
 * </pre>
 */
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT = join(HERE, 'demo-narration.json')
const OUT = join(HERE, 'narration')
const FORCE = process.argv.includes('--force')

const KEY = process.env.ELEVENLABS_API_KEY
if (!KEY) {
  console.error(`
No ELEVENLABS_API_KEY in the environment.

  export $(grep ELEVENLABS .env | xargs)

.env is already gitignored (.gitignore:10) and untracked, so the key belongs there rather than in
a new file. Do not pass it as an argument — arguments are visible in the process list.
`)
  process.exit(1)
}

const spec = JSON.parse(readFileSync(SCRIPT, 'utf8'))
const { voice, beats } = spec

if (!existsSync(OUT)) {
  mkdirSync(OUT, { recursive: true })
}

/**
 * Rough spoken duration from character count.
 *
 * <p>~14 characters per second at a measured narration pace. Only used to flag a beat as long
 * BEFORE spending an API call on it — the real number comes from the rendered file below, and this
 * is deliberately not precise enough to be trusted for anything else.
 */
function estimateSeconds(text) {
  return text.length / 14
}

/**
 * Duration of an MP3, from its bitrate and size.
 *
 * <p>ElevenLabs returns CBR at a known rate, so size over bitrate is exact enough to compare
 * against a target. Parsing frame headers would be more correct and would tell us nothing more
 * than whether a beat overran.
 */
function measureSeconds(path, bitrateKbps = 128) {
  return (statSync(path).size * 8) / (bitrateKbps * 1000)
}

async function synthesise(beat) {
  const url = `https://api.elevenlabs.io/v1/text-to-speech/${voice.voiceId}`
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      // The one place the key is used. Never logged, never echoed.
      'xi-api-key': KEY,
      'Content-Type': 'application/json',
      Accept: 'audio/mpeg',
    },
    body: JSON.stringify({
      text: beat.text,
      model_id: voice.modelId,
      voice_settings: {
        stability: voice.stability,
        similarity_boost: voice.similarityBoost,
        style: voice.style,
        use_speaker_boost: true,
      },
    }),
  })

  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    // The body can echo the request; truncated so a verbose error cannot become a way to leak one.
    throw new Error(`${response.status} ${response.statusText} — ${detail.slice(0, 200)}`)
  }
  return Buffer.from(await response.arrayBuffer())
}

// What the mp3s on disk were actually rendered with, from the last run's manifest. Absent or
// unreadable means "unknown", which re-renders rather than trusting them -- the cost of a needless
// re-render is a few thousand characters, and the cost of trusting a stale file is a wrong-voice
// video that looks finished.
const previousVoice = (() => {
  try {
    return JSON.parse(readFileSync(join(OUT, 'manifest.json'), 'utf8')).voice
  } catch {
    return null
  }
})()

if (previousVoice && previousVoice !== voice.voiceId) {
  console.log(`  voice changed ${previousVoice} -> ${voice.voiceId}; re-rendering every beat
`)
}

const manifest = []
let spent = 0

for (const beat of beats) {
  const path = join(OUT, `${beat.id}.mp3`)

  // The cache keys on the beat id AND the voice it was rendered with. Id alone is not enough: the
  // text and the voice can both change under a stable id, and a stale mp3 is silent about which.
  // Changing voiceId and re-running skipped all thirteen beats as "already rendered" and left the
  // manifest claiming the NEW voice over audio in the old one -- the video would have shipped in
  // the wrong voice with nothing on screen or in the logs to say so.
  if (existsSync(path) && !FORCE && previousVoice === voice.voiceId) {
    const actual = measureSeconds(path)
    console.log(`  skip   ${beat.id.padEnd(12)} ${actual.toFixed(1)}s (already rendered)`)
    manifest.push({ id: beat.id, file: `narration/${beat.id}.mp3`, seconds: actual, target: beat.seconds })
    continue
  }

  const estimate = estimateSeconds(beat.text)
  process.stdout.write(`  render ${beat.id.padEnd(12)} ~${estimate.toFixed(1)}s … `)

  try {
    const audio = await synthesise(beat)
    writeFileSync(path, audio)
    spent += beat.text.length

    const actual = measureSeconds(path)
    const over = actual - beat.seconds
    const flag = over > 1.5 ? `  OVER by ${over.toFixed(1)}s` : ''
    console.log(`${actual.toFixed(1)}s${flag}`)
    manifest.push({ id: beat.id, file: `narration/${beat.id}.mp3`, seconds: actual, target: beat.seconds })
  } catch (e) {
    console.log(`FAILED — ${e.message}`)
    // Carry on. One failed beat should not cost the six that already rendered, and re-running
    // picks up only what is missing.
  }
}

writeFileSync(join(OUT, 'manifest.json'), JSON.stringify({ voice: voice.voiceId, beats: manifest }, null, 2))

const totalActual = manifest.reduce((sum, b) => sum + b.seconds, 0)
const totalTarget = manifest.reduce((sum, b) => sum + b.target, 0)
const overruns = manifest.filter((b) => b.seconds - b.target > 1.5)

console.log(`
  ${manifest.length}/${beats.length} beats in ${OUT}
  ${totalActual.toFixed(1)}s narrated against ${totalTarget}s of capture
  ${spent} characters billed this run`)

if (overruns.length > 0) {
  console.log(`
  These beats run longer than the capture holds for:`)
  for (const b of overruns) {
    console.log(`    ${b.id.padEnd(12)} ${b.seconds.toFixed(1)}s vs ${b.target}s`)
  }
  console.log(`
  Either trim the text in demo-narration.json, or raise the matching value in BEAT in
  demo-capture.spec.js and re-record. Not corrected automatically: which one is right depends on
  whether the words or the footage are doing the work in that beat.`)
}
