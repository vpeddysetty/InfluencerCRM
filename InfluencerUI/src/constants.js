export const STAGES = ['outreach', 'agreed', 'shipped', 'posted', 'paid']

export const stageLabels = {
  outreach: 'Outreach',
  agreed: 'Agreed',
  shipped: 'Shipped',
  posted: 'Posted',
  paid: 'Paid',
}

export const starterCampaigns = [
  { id: 'c-001', name: 'Summer Glow Launch', budget: '24000', status: 'active' },
  { id: 'c-002', name: 'Founders Edit Capsule', budget: '12000', status: 'draft' },
]

export const starterCreators = [
  { id: 'r-001', name: 'Lena Park', handle: '@lenalights', platform: 'Instagram', email: 'lena@sample.com' },
  { id: 'r-002', name: 'Mason Vale', handle: '@masonslate', platform: 'TikTok', email: 'mason@sample.com' },
]

export const starterAssignments = [
  {
    id: 'a-001',
    campaignId: 'c-001',
    creatorId: 'r-001',
    stage: 'agreed',
    fee: '950',
    notes: 'Ready for first shipment',
    dueDate: '2026-07-25',
    tags: ['priority', 'instagram'],
  },
  {
    id: 'a-002',
    campaignId: 'c-001',
    creatorId: 'r-002',
    stage: 'outreach',
    fee: '700',
    notes: 'Waiting on rate card',
    dueDate: '2026-07-31',
    tags: ['new-contact', 'tiktok'],
  },
]

export function parseCsv(text) {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)

  if (!lines.length) {
    return { headers: [], rows: [] }
  }

  const splitLine = (line) => {
    const cells = []
    let current = ''
    let inQuotes = false

    for (let i = 0; i < line.length; i += 1) {
      const char = line[i]
      const next = line[i + 1]

      if (char === '"') {
        if (inQuotes && next === '"') {
          current += '"'
          i += 1
        } else {
          inQuotes = !inQuotes
        }
      } else if (char === ',' && !inQuotes) {
        cells.push(current.trim())
        current = ''
      } else {
        current += char
      }
    }

    cells.push(current.trim())
    return cells
  }

  const [headerLine, ...bodyLines] = lines
  const headers = splitLine(headerLine)
  const rows = bodyLines.map(splitLine)

  return { headers, rows }
}
