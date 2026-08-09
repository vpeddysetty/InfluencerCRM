import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import ContentPage from './ContentPage'

/**
 * Standalone harness for developing this remote without the gateway.
 *
 * The gateway normally supplies session and data through props. Here they are stubbed, which is
 * enough to iterate on layout and interaction — not a substitute for running against the platform.
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <ContentPage />
    </BrowserRouter>
  </StrictMode>,
)
