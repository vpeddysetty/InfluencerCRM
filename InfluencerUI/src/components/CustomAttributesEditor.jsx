import { useEffect, useMemo, useRef, useState } from 'react'

function normalizeKey(rawKey) {
  return String(rawKey || '').trim().toLowerCase()
}

function createRowId(sequence) {
  return `attr-${sequence}`
}

function CustomAttributesEditor({
  pairs,
  onChange,
  addLabel = 'Add attribute',
  onValidationChange,
}) {
  const safePairs = Array.isArray(pairs) ? pairs : []
  const [dragIndex, setDragIndex] = useState(null)
  const rowIdSequenceRef = useRef(0)

  const withRowId = (pair) => {
    if (pair?._rowId) {
      return pair
    }

    rowIdSequenceRef.current += 1
    return {
      ...pair,
      _rowId: createRowId(rowIdSequenceRef.current),
    }
  }

  useEffect(() => {
    if (!safePairs.length) {
      return
    }

    const hasMissingRowId = safePairs.some((pair) => !pair?._rowId)
    if (!hasMissingRowId) {
      return
    }

    onChange(safePairs.map((pair) => withRowId(pair)))
  }, [safePairs, onChange])

  const duplicateIndexes = useMemo(() => {
    const seen = new Map()
    const duplicates = new Set()

    safePairs.forEach((pair, index) => {
      const normalized = normalizeKey(pair?.key)
      if (!normalized) {
        return
      }

      if (seen.has(normalized)) {
        duplicates.add(index)
        duplicates.add(seen.get(normalized))
        return
      }

      seen.set(normalized, index)
    })

    return duplicates
  }, [safePairs])

  const missingNameIndexes = useMemo(() => {
    const missing = new Set()

    safePairs.forEach((pair, index) => {
      const key = String(pair?.key || '').trim()
      const value = String(pair?.value || '').trim()
      if (!key && value) {
        missing.add(index)
      }
    })

    return missing
  }, [safePairs])

  const hasDuplicateKeys = duplicateIndexes.size > 0
  const hasMissingKeys = missingNameIndexes.size > 0

  useEffect(() => {
    if (onValidationChange) {
      onValidationChange({ hasDuplicateKeys, hasMissingKeys })
    }
  }, [hasDuplicateKeys, hasMissingKeys, onValidationChange])

  const updatePair = (index, field, value) => {
    const next = safePairs.map((pair, pairIndex) => {
      if (pairIndex !== index) {
        return withRowId(pair)
      }
      return {
        ...withRowId(pair),
        [field]: value,
      }
    })
    onChange(next)
  }

  const addPair = () => {
    rowIdSequenceRef.current += 1
    onChange([
      ...safePairs,
      { key: '', value: '', type: 'text', _rowId: createRowId(rowIdSequenceRef.current) },
    ])
  }

  const removePair = (index) => {
    onChange(safePairs.filter((_, pairIndex) => pairIndex !== index))
  }

  const movePair = (fromIndex, toIndex) => {
    if (fromIndex == null || toIndex == null || fromIndex === toIndex) {
      return
    }

    const next = safePairs.map((pair) => withRowId(pair))
    const [moved] = next.splice(fromIndex, 1)
    next.splice(toIndex, 0, moved)
    onChange(next)
  }

  const handleDragStart = (index) => {
    setDragIndex(index)
  }

  const handleDrop = (dropIndex) => {
    movePair(dragIndex, dropIndex)
    setDragIndex(null)
  }

  return (
    <div className="custom-attributes-editor">
      {safePairs.length ? (
        safePairs.map((pair, index) => (
          <div
            className={`custom-attribute-row ${dragIndex === index ? 'dragging' : ''}`}
            key={pair._rowId || `attr-row-${index}`}
            draggable
            onDragStart={() => handleDragStart(index)}
            onDragOver={(event) => event.preventDefault()}
            onDrop={() => handleDrop(index)}
            onDragEnd={() => setDragIndex(null)}
          >
            <span className="custom-attribute-handle" title="Drag to reorder" aria-hidden="true">::</span>
            <input
              type="text"
              className={`custom-attribute-name ${duplicateIndexes.has(index) || missingNameIndexes.has(index) ? 'invalid' : ''}`}
              value={pair.key || ''}
              placeholder="Attribute name"
              onChange={(event) => updatePair(index, 'key', event.target.value)}
            />
            <select
              className="custom-attribute-type"
              value={pair.type || 'text'}
              onChange={(event) => updatePair(index, 'type', event.target.value)}
            >
              <option value="text">Text</option>
              <option value="number">Number</option>
              <option value="boolean">Boolean</option>
            </select>
            {pair.type === 'boolean' ? (
              <select
                className="custom-attribute-value"
                value={String(pair.value == null || pair.value === '' ? 'false' : pair.value)}
                onChange={(event) => updatePair(index, 'value', event.target.value)}
              >
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            ) : (
              <input
                type={pair.type === 'number' ? 'number' : 'text'}
                className="custom-attribute-value"
                value={pair.value == null ? '' : String(pair.value)}
                placeholder="Attribute value"
                onChange={(event) => updatePair(index, 'value', event.target.value)}
              />
            )}
            <button
              type="button"
              className="ghost-btn custom-attribute-remove"
              onClick={() => removePair(index)}
              aria-label={`Remove attribute ${pair.key || index + 1}`}
            >
              Remove
            </button>
            {duplicateIndexes.has(index) ? <p className="custom-attribute-error">Duplicate attribute name. Use a unique name.</p> : null}
            {missingNameIndexes.has(index) ? <p className="custom-attribute-error">Attribute name is required when value is provided.</p> : null}
          </div>
        ))
      ) : (
        <p className="custom-attributes-empty">No custom attributes yet.</p>
      )}
      {hasDuplicateKeys ? <p className="custom-attribute-error">Resolve duplicate names before saving.</p> : null}
      {hasMissingKeys ? <p className="custom-attribute-error">Every non-empty value must have an attribute name.</p> : null}
      <button type="button" className="ghost-btn custom-attribute-add" onClick={addPair}>
        {addLabel}
      </button>
    </div>
  )
}

export default CustomAttributesEditor
