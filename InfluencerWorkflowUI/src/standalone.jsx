import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import WorkflowPage from './WorkflowPage'

/**
 * Standalone harness for developing this remote without the shell.
 *
 * The shell normally supplies session and workflow state through props/context. Here they are
 * stubbed so the page renders in isolation — enough to iterate on layout and interaction, not a
 * substitute for running against the real platform.
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <WorkflowPage
        boards={[]}
        stages={[]}
        cards={[]}
        activeBoardId=""
        onSelectBoard={() => {}}
        onCreateBoard={() => {}}
        onUpdateBoard={() => {}}
        onDeleteBoard={() => {}}
        onReplaceStages={() => {}}
        onCreateCard={() => {}}
        onPlaceCard={() => {}}
        onDeleteCard={() => {}}
      />
    </BrowserRouter>
  </StrictMode>,
)
