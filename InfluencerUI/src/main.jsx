import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.jsx'
import { GatewayProvider } from './shell/gateway/GatewayContext'

/**
 * The gateway wraps everything, including the router.
 *
 * Session state has to exist before any route renders — a remote that mounts before the gateway is
 * ready would try to fetch unauthenticated and get a 401 on first paint. Wrapping at the root is
 * what makes the session available to every route and every federated remote from the first render.
 */
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <GatewayProvider>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </GatewayProvider>
  </StrictMode>,
)
