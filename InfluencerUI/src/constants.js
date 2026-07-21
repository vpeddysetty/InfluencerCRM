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

function normalizeHeader(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

function inferMappingForHeader(header) {
  const normalized = normalizeHeader(header)

  const mappingRules = [
    { match: ['campaign name', 'campaign'], targetEntity: 'campaign', targetAttribute: 'name' },
    { match: ['campaign budget', 'budget'], targetEntity: 'campaign', targetAttribute: 'budget' },
    { match: ['campaign status'], targetEntity: 'campaign', targetAttribute: 'status' },
    { match: ['campaign custom attributes', 'campaign customer attributes'], targetEntity: 'campaign', targetAttribute: 'customAttributes' },
    { match: ['creator name'], targetEntity: 'creator', targetAttribute: 'name' },
    { match: ['creator handle', 'handle'], targetEntity: 'creator', targetAttribute: 'handle' },
    { match: ['platform', 'creator platform'], targetEntity: 'creator', targetAttribute: 'platform' },
    { match: ['email', 'creator email'], targetEntity: 'creator', targetAttribute: 'email' },
    { match: ['creator custom attributes', 'creator customer attributes', 'custom attributes', 'customer attributes'], targetEntity: 'creator', targetAttribute: 'customAttributes' },
    { match: ['stage', 'workflow stage'], targetEntity: 'campaign_creator', targetAttribute: 'stage' },
    { match: ['agreed fee', 'fee', 'rate'], targetEntity: 'campaign_creator', targetAttribute: 'agreedFee' },
    { match: ['content due at', 'content due date', 'due date'], targetEntity: 'campaign_creator', targetAttribute: 'contentDueAt' },
    { match: ['discount code', 'code'], targetEntity: 'campaign_creator', targetAttribute: 'discountCode' },
    { match: ['link', 'landing url'], targetEntity: 'campaign_creator', targetAttribute: 'link' },
    { match: ['post url'], targetEntity: 'campaign_creator', targetAttribute: 'postUrl' },
    { match: ['campaign relationship custom attributes', 'campaign relationship customer attributes'], targetEntity: 'campaign_creator', targetAttribute: 'customAttributes' },
  ]

  const matchedRule = mappingRules.find((rule) => rule.match.includes(normalized))
  if (!matchedRule) {
    return {
      spreadsheetColumn: header,
      targetEntity: 'campaign',
      targetAttribute: '',
    }
  }

  return {
    spreadsheetColumn: header,
    targetEntity: matchedRule.targetEntity,
    targetAttribute: matchedRule.targetAttribute,
  }
}

function buildRowObjects(headers, rows) {
  return rows
    .filter((row) => row.some((cell) => String(cell || '').trim() !== ''))
    .map((row) => headers.reduce((acc, header, index) => {
      acc[header] = row[index] ?? ''
      return acc
    }, {}))
}

export async function parseSpreadsheetFile(file) {
  const extension = file.name.split('.').pop()?.toLowerCase()

  if (extension === 'csv') {
    const text = await file.text()
    const parsed = parseCsv(text)
    return {
      type: 'CSV',
      headers: parsed.headers,
      rows: parsed.rows,
      rowObjects: buildRowObjects(parsed.headers, parsed.rows),
    }
  }

  if (extension === 'xls' || extension === 'xlsx') {
    const XLSX = await import('xlsx')
    const buffer = await file.arrayBuffer()
    const workbook = XLSX.read(buffer, { type: 'array' })
    const firstSheetName = workbook.SheetNames[0]
    const firstSheet = workbook.Sheets[firstSheetName]
    const matrix = XLSX.utils.sheet_to_json(firstSheet, { header: 1, raw: false, defval: '' })
    const [headerRow = [], ...bodyRows] = matrix
    const headers = headerRow.map((value) => String(value || '').trim()).filter(Boolean)
    const rows = bodyRows.map((row) => headers.map((_, index) => row[index] ?? ''))

    return {
      type: extension.toUpperCase(),
      headers,
      rows,
      rowObjects: buildRowObjects(headers, rows),
    }
  }

  throw new Error('Unsupported file type. Please upload CSV, XLS, or XLSX.')
}

export function createImportMappingJson(headers) {
  return JSON.stringify(headers.map(inferMappingForHeader), null, 2)
}

export function createImportMappingJsonFromAgent(headers, recommendations) {
  const mappedByColumn = new Map(
    (Array.isArray(recommendations) ? recommendations : [])
      .filter((item) => item && item.spreadsheet_column)
      .map((item) => [item.spreadsheet_column, item]),
  )

  return JSON.stringify(
    headers.map((header) => {
      const mapped = mappedByColumn.get(header)
      if (!mapped) {
        return inferMappingForHeader(header)
      }
      return {
        spreadsheetColumn: header,
        targetEntity: mapped.target_entity || 'campaign',
        targetAttribute: mapped.target_attribute || '',
      }
    }),
    null,
    2,
  )
}
