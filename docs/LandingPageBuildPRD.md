# Influencer Campaign Landing Page Builder

---

## Overview

**Product name**: Influencer Campaign Landing Page Builder (ICLPB)  
**Purpose**: A role‑aware landing page builder that unifies brand owners, creators, and campaign workflows. The platform supports AI‑driven creator onboarding, influencer CRM integration, workflow automation with Kanban synchronization, domain provisioning and hosting, and mobile and tablet responsive pages with multi‑device previews.  
**Primary users**: Brand owners, agencies, creators, campaign managers.

---

## Goals

**Primary goals**
- Enable brand owners to build landing pages and publish to social media or custom domains.  
- Provide creators a portal to sign up, collaborate, and co‑create landing pages.  
- Support AI‑driven creator onboarding using social handle interpretation.  
- Capture creator signups as leads in the Influencer CRM.  
- Establish brand–creator relationships for campaign co‑ownership.  
- Automatically sync landing page stages with Kanban workflow.  
- Ensure landing pages are responsive on mobile, tablet, and desktop.  
- Provide multi‑device previews inside the builder.  
- Allow brands to provision new domains or connect existing domains for hosting.

**Secondary goals**
- Reduce manual campaign management overhead.  
- Improve creator vetting efficiency.  
- Provide a unified workspace for brand–creator collaboration.  
- Enable scalable influencer onboarding.

---

## User Roles and Permissions

**Brand Owner or Agency**
- Create and edit landing pages.  
- Provision or connect domains and host pages.  
- Publish pages to social platforms.  
- Configure creator signup portal.  
- Review and vet creators.  
- Approve creators and trigger welcome packages.  
- Manage campaigns and view Kanban board.  
- Invite creators to co‑create pages.

**Creator**
- Sign up via AI handle parsing or manual form.  
- Build creator‑specific landing pages and co‑edit shared pages.  
- Accept campaign invitations and view assigned tasks.  
- Access creator portal, asset library, and campaign briefs.

**Brand and Creator Campaign Pair**
- Shared ownership of landing pages.  
- Shared workspace and content library.  
- Shared Kanban workflow and stage synchronization.

---

## Core Features

### Role Aware Landing Page Builder
- **Adaptive UI** that changes available blocks and templates based on role.  
- **Brand templates**, **creator templates**, and **campaign templates**.  
- **Drag and drop editor** with real time preview.  
- **Dynamic blocks** for creator signup, product catalog, UGC, and CTAs.  
- **Version control** and **commenting** for collaborative editing.

### Mobile and Tablet Compatibility
- **Mobile first layout engine** with responsive breakpoints.  
- **Tablet optimized layouts** and touch friendly interactions.  
- **Adaptive image scaling**, responsive typography, and responsive CTA placement.  
- **Breakpoint editor** to fine tune mobile and tablet layouts.  
- **AI layout optimizer** that suggests mobile improvements.

### Multi Device Preview System
- Toggleable preview modes: **Mobile**, **Tablet**, **Desktop**.  
- Simulated orientation, safe areas, touch zones, and CTA visibility.  
- Preview for different viewport sizes and network conditions.

### Domain Provisioning and Hosting
- **Domain provisioning wizard** to purchase and configure new domains via partners.  
- **Custom domain connection** for existing domains with DNS validation.  
- **Automatic SSL generation and renewal**.  
- **Subdomain assignment** for campaigns (example campaign.brand.com).  
- **Hosting deployment engine** with CDN distribution, caching, and zero downtime deploys.  
- **Rollback and version history** for published pages.

### Creator Signup System
- **AI driven onboarding**: creator pastes handle, AI interprets profile, extracts metrics, classifies niche, scores brand fit, flags risks, and creates lead in CRM.  
- **Manual signup form**: name, email, handles, niche, audience size, portfolio, past collaborations.  
- Both flows create structured leads in the Influencer CRM.

### Creator Vetting and Welcome Package
- Vetting stages: **Lead created**, **Pending vetting**, **Under review**, **Approved**, **Rejected**.  
- On approval, automated **welcome package** delivery containing brand guidelines, campaign brief, asset access, and onboarding instructions.  
- Approved creators added to campaign pool and assigned tasks.

### Campaign Workflow Engine and Kanban Sync
- Landing page stages drive workflow stages.  
- Landing page stages: **Draft**, **Review**, **Approved**, **Creator Assigned**, **Content Needed**, **Ready to Publish**, **Published**, **Performance Tracking**.  
- **Automatic Kanban synchronization**: stage changes move Kanban cards, generate tasks, and notify stakeholders.  
- Support for custom stage mappings and automation rules.

### Influencer CRM Integration
- Lead ingestion API and data model mapping.  
- AI scoring fields stored in CRM.  
- Campaign assignment and relationship graph between brands and creators.  
- Landing page metadata and Kanban sync state persisted in CRM.  
- Creator performance metrics and historical campaign data.

### Social Publishing
- One click or scheduled publishing to Instagram, TikTok, YouTube, Facebook, Pinterest.  
- Publishing triggers stage updates and Kanban movements.  
- Post metadata and performance tracking stored in CRM.

### Shared Workspace
- Shared editor, chat, asset library, task list, campaign brief, and version history.  
- Role based access control for editing, commenting, and publishing.

---

## System Architecture

### High Level Components
- **Landing Page Builder** with responsive layout engine.  
- **Device Preview Renderer** for multi‑device simulation.  
- **Domain Provisioning Service** and domain purchase integrations.  
- **Hosting Deployment Engine** with CDN and SSL manager.  
- **AI Creator Onboarding Engine** for handle parsing and scoring.  
- **Influencer CRM** for lead storage and relationship graph.  
- **Workflow Engine** for stage driven automation.  
- **Kanban Board** UI and sync service.  
- **Social Publishing Engine** for platform integrations.  
- **Shared Workspace Service** for collaboration and assets.

### Data Models

| Entity | Key Fields |
|---|---|
| Brand | BrandID; Name; SocialHandles; CampaignIDs |
| Creator | CreatorID; Handles; AIScore; Niche; AudienceMetrics; Status |
| Campaign | CampaignID; BrandID; CreatorIDs; LandingPageID; Stage |
| Landing Page | PageID; OwnerRole; Stage; Blocks; ResponsiveSettings; DomainSettings; PublishedURLs |
| DomainSettings | DomainName; Subdomain; DNSStatus; SSLStatus; HostingEnvironment |
| Kanban Card | CardID; Stage; AssignedTo; Tasks |

---

## Recommended Tech Stack

### Summary
Provide a pragmatic, production‑ready stack that supports:
- **Visual, drag‑and‑drop builders** with real‑time previews.  
- **Mobile, tablet, and desktop responsive rendering** and breakpoint editing.  
- **AI integration** for onboarding, copy generation, and layout suggestions.  
- **Scalable hosting and domain provisioning** with CDN and SSL automation.  
- **Seamless integration** with an existing C# Influencer CRM (C:\AI\InfluencerCRM) where applicable.

Two recommended approaches are presented: **Primary (JavaScript/TypeScript full‑stack)** for rapid iteration and rich visual tooling, and **Alternative (.NET‑centric)** for teams that prefer C# and want to reuse the existing CRM codebase.

---

### Primary Recommendation (MVP → Scale): JavaScript / TypeScript Full‑Stack

**Frontend**
- **Framework**: **React** + **Next.js** (server‑side rendering, static export, routing, edge functions).  
- **Styling**: **Tailwind CSS** for utility‑first responsive design; CSS Grid/Flexbox for layout.  
- **Visual Builder**: **GrapesJS** (open source visual builder) or **Builder.io** (commercial) integrated into React; custom blocks implemented with React components.  
- **Drag & Drop**: **React DnD** or **dnd-kit** for custom block interactions.  
- **Editor State**: Immutable JSON document model (blocks schema) persisted to backend; use **Yjs** or **Operational Transformation** for real‑time collaboration.  
- **Preview Renderer**: Isolated iframe renderer for accurate device previews; server‑side snapshot rendering for social previews.

**Backend**
- **Runtime**: **Node.js** with **NestJS** (structured, modular) or **Express** for lighter weight.  
- **API**: REST + GraphQL (Apollo) for flexible data queries (GraphQL recommended for complex editor state).  
- **AI Microservices**: Python microservices (FastAPI) for heavy ML tasks OR Node.js wrappers calling managed AI APIs. Use **OpenAI / Azure OpenAI** for LLM tasks and **Hugging Face** for model hosting if needed.  
- **Queueing**: **RabbitMQ** or **BullMQ (Redis)** for background jobs (AI jobs, publishing, domain provisioning).  
- **Search**: **Elasticsearch** or **OpenSearch** for creator discovery and fast queries.

**Data Storage**
- **Primary DB**: **PostgreSQL** (relational data, transactions).  
- **Cache / Session**: **Redis**.  
- **Object Storage**: **S3** (AWS) or compatible (Azure Blob, GCS) for images, assets, published page bundles.  
- **Analytics / Events**: **ClickHouse** or **BigQuery** for large scale analytics.

**Hosting & Infra**
- **Frontend Hosting**: **Vercel** (Next.js optimized) or Netlify for static/edge deployments.  
- **Backend & AI**: **AWS** (ECS/EKS/Lambda), **Azure** (App Service/AKS), or **GCP** (Cloud Run/GKE). Choose based on existing infra and compliance.  
- **CDN**: **Cloudflare** or AWS CloudFront.  
- **DNS / Domain**: **Route 53** (AWS) or **Cloudflare Registrar**; integrate registrar APIs for provisioning.  
- **SSL**: Automated via Let's Encrypt or managed by Cloudflare/AWS Certificate Manager.  
- **CI/CD**: **GitHub Actions** or GitLab CI for pipelines, tests, and deployments.  
- **Observability**: **Prometheus + Grafana**, **Sentry** for errors, **ELK** or **Datadog** for logs and traces.

**AI & ML**
- **LLM Provider**: **OpenAI** or **Azure OpenAI** for text generation, handle interpretation, and scoring.  
- **Embeddings & Search**: OpenAI embeddings or Hugging Face + FAISS for semantic search.  
- **Orchestration**: **LangChain** (Node/Python) for chaining LLM calls and prompt management.  
- **Model Hosting**: Hugging Face Inference or self‑hosted models on GPU instances for custom models.

**Integrations**
- **Social APIs**: Instagram Graph API, TikTok for Developers, YouTube Data API, Facebook Graph API, Pinterest API.  
- **Payments / Contracts**: Stripe for payments; DocuSign or HelloSign for contracts.  
- **Email / Notifications**: SendGrid, Postmark, or SES; Twilio for SMS.

**Why this stack**
- React + Next.js gives fast developer velocity, excellent SSR/SSG for SEO, and easy integration with visual builders.  
- Tailwind accelerates responsive UI development.  
- GrapesJS or Builder.io provides a mature visual builder foundation to avoid building from scratch.  
- Managed AI providers speed up AI features and reduce ops burden.  
- PostgreSQL + S3 + Redis is a proven combination for reliability and scale.

---

### Alternative Recommendation: .NET / C# Centric (for teams reusing C:\AI\InfluencerCRM)

**Frontend**
- **Framework**: **React** (same reasons) or **Blazor** if the team prefers C# end‑to‑end. React + Next.js still recommended for visual builder ecosystem.  
- **Visual Builder**: GrapesJS integrated into React; if using Blazor, embed a JS visual builder and interop.

**Backend**
- **Runtime**: **.NET 7+ (ASP.NET Core)** to reuse existing CRM codebase and libraries.  
- **API**: REST + GraphQL (HotChocolate) for flexible queries.  
- **AI Microservices**: Python or .NET wrappers calling OpenAI/Azure OpenAI. Azure OpenAI integrates natively with .NET.  
- **Queueing**: **Azure Service Bus** or **RabbitMQ**.  
- **Search**: **Azure Cognitive Search** or Elasticsearch.

**Data Storage**
- **Primary DB**: **SQL Server** or **PostgreSQL** (both supported by .NET).  
- **Object Storage**: **Azure Blob Storage**.  
- **Cache**: **Azure Cache for Redis**.

**Hosting & Infra**
- **Platform**: **Azure** (App Service, AKS, Functions) for tight .NET integration.  
- **CDN**: **Azure CDN** or Cloudflare.  
- **Domain & SSL**: Azure DNS + App Service managed certificates.

**Why this stack**
- Reuses existing C# code and reduces migration risk.  
- Azure provides first‑class support for .NET and Azure OpenAI for AI features.

---

### Visual Builder Options and Libraries

**Off‑the‑shelf**
- **GrapesJS** — open source, extensible visual builder for landing pages. Good for MVP and custom block development.  
- **Builder.io** — commercial visual CMS with drag‑and‑drop and headless delivery.

**Custom**
- **React + dnd-kit / React DnD** for block interactions.  
- **Slate** or **ProseMirror** for rich text editing inside blocks.  
- **Yjs** for real‑time collaboration and conflict resolution.

**Recommendation**
- Start with **GrapesJS** or **Builder.io** to accelerate delivery; extend with custom React components for brand/creator blocks and AI hooks.

---

### MVP Stack Recommendation (fastest path to working product)
- **Frontend**: React + Next.js + Tailwind + GrapesJS (embedded).  
- **Backend**: Node.js + NestJS (REST + GraphQL).  
- **DB**: PostgreSQL.  
- **Cache/Queue**: Redis + BullMQ.  
- **Storage**: S3.  
- **AI**: OpenAI / Azure OpenAI (for handle parsing, copy, layout suggestions).  
- **Hosting**: Vercel (frontend) + AWS (backend) or Vercel + Azure if using Azure OpenAI.  
- **CI/CD**: GitHub Actions.  
- **Observability**: Sentry + Prometheus/Grafana.

---

## Tech Stack Rationale and Mapping to PRD Components

| PRD Component | Recommended Tech |
|---|---|
| Visual landing page editor | React + GrapesJS; custom React blocks |
| Device preview renderer | Isolated iframe renderer + Next.js preview routes |
| Responsive engine & breakpoint editor | Tailwind CSS + custom breakpoint editor UI |
| AI onboarding & scoring | OpenAI / Azure OpenAI; LangChain orchestration |
| Lead ingestion & CRM sync | REST/GraphQL APIs; PostgreSQL; background jobs (BullMQ) |
| Domain provisioning | Registrar APIs (Cloudflare / Route53) + DNS helper service |
| Hosting & CDN | Vercel (frontend) + CloudFront/Cloudflare (CDN) + S3 for assets |
| Social publishing | Platform APIs + background queue + retry logic |
| Real‑time collaboration | Yjs + WebSocket (WS) or WebRTC |
| Search & discovery | Elasticsearch / OpenSearch |
| Analytics | ClickHouse / BigQuery for event analytics |
| Observability | Sentry, Prometheus, Grafana, ELK/Datadog |

---

## Implementation Notes and Tradeoffs

- **Use a visual builder library** to reduce time to market. Building a robust visual editor from scratch is expensive and error prone. GrapesJS is a strong open source starting point; Builder.io is faster but commercial.  
- **Managed AI services** (OpenAI/Azure) accelerate feature delivery and reduce ops. If data residency or cost requires self‑hosting, plan for GPU infrastructure and model ops.  
- **Next.js + Vercel** simplifies SSR/SSG and preview flows; if the team uses Azure heavily, Next.js on Azure Static Web Apps or Azure Static Web Apps + Functions is viable.  
- **If the existing CRM is C#** and deep integration is required, prefer a .NET backend or a hybrid approach where the CRM remains .NET and new services are Node/Python microservices that communicate via APIs or message bus.  
- **Real‑time collaboration** adds complexity; scope it for Phase 2 unless required for MVP. Use Yjs for conflict resolution and CRDTs.  
- **Domain provisioning** requires careful UX and robust DNS validation; use registrar partners and provide clear DNS instructions and automated checks.  
- **Social publishing** requires handling rate limits, token refresh, and platform policy compliance; implement robust retry and error handling.

---

## Roadmap and Execution Plan (updated with tech stack mapping)

**Phase 0 Preparation**
- Finalize PRD and acceptance criteria.  
- Audit existing Influencer CRM codebase and APIs; decide integration pattern (direct DB, API, or message bus).  
- Choose primary cloud provider (AWS/Azure/GCP) and domain registrar partner.  
- Select visual builder approach (GrapesJS vs Builder.io).

**Phase 1 Core Builder and CRM Integration**
- Implement Next.js frontend shell and Tailwind design system.  
- Integrate GrapesJS into React and implement core block schema.  
- Implement backend APIs (NestJS or ASP.NET Core) and PostgreSQL schema.  
- Implement manual creator signup and CRM lead ingestion API.  
- Implement basic Kanban board and stage mapping.

**Phase 2 AI Onboarding and Vetting**
- Implement AI microservice (FastAPI or Node) that calls OpenAI/Azure OpenAI.  
- Build handle parsing, audience metrics extraction, and AIScore pipeline.  
- Integrate AI outputs into CRM lead model and vetting UI.  
- Implement welcome package automation.

**Phase 3 Responsive Engine and Previews**
- Implement breakpoint editor and device preview renderer (iframe approach).  
- Add AI layout optimizer for responsive suggestions.  
- Implement Yjs for collaborative editing if required.

**Phase 4 Domain Provisioning and Hosting**
- Implement domain wizard and registrar integration (Cloudflare/Route53).  
- Implement hosting deployment engine (Vercel + backend deploys) and CDN configuration.  
- Implement SSL automation and deployment logs.

**Phase 5 Social Publishing and Shared Workspace**
- Integrate social platform APIs and scheduling.  
- Implement shared workspace features: chat, asset library, version history.  
- Finalize Kanban automation rules and task generation.

**Phase 6 Hardening and Launch**
- Performance optimization and load testing.  
- Security audit and compliance checks.  
- Beta launch with pilot brands and creators.  
- Iterate on feedback and prepare general availability release.

---

## Acceptance Criteria (updated to include tech stack)

**Landing Page Builder**
- Users can create and save pages with role specific templates.  
- Editor supports drag and drop and real time preview across mobile, tablet, desktop.  
- Pages render correctly on device previews and on assigned domains.

**Creator Signup**
- AI onboarding accepts a handle and creates a CRM lead with AIScore.  
- Manual signup creates a CRM lead with required fields.

**Domain Provisioning**
- Brands can provision a new domain or connect an existing domain.  
- DNS validation and SSL provisioning complete automatically.  
- Landing page deploys to the domain and is reachable over HTTPS.

**Kanban Sync**
- Changing landing page stage moves Kanban card automatically.  
- Tasks are generated for stage transitions and assigned correctly.

**Publishing**
- Pages can be published to supported social platforms and to brand domains.  
- Publishing updates stage and Kanban state.

**Tech Stack**
- Frontend built with React + Next.js and Tailwind.  
- Visual builder integrated (GrapesJS or Builder.io) and supports custom blocks.  
- Backend APIs implemented (NestJS or ASP.NET Core) with PostgreSQL.  
- AI features integrated using OpenAI/Azure OpenAI and background job processing.  
- Hosting and domain provisioning flows validated end‑to‑end.

---

## Next Steps

1. Confirm acceptance of PRD and sign off on tech stack choice (Primary JS stack or .NET variant).  
2. Assign engineering, design, and data science leads and select cloud provider.  
3. Create a technical spike to integrate GrapesJS into Next.js and to prototype AI handle parsing with OpenAI.  
4. Schedule architecture and API design sessions and prepare development backlog.

---

## Appendix: Quick Implementation Checklist (Tech)

- [ ] Choose visual builder (GrapesJS / Builder.io).  
- [ ] Scaffold Next.js + Tailwind frontend.  
- [ ] Scaffold backend (NestJS or ASP.NET Core) and PostgreSQL schema.  
- [ ] Implement lead ingestion API and map to C:\AI\InfluencerCRM integration plan.  
- [ ] Prototype AI handle parsing using OpenAI/Azure OpenAI.  
- [ ] Implement device preview iframe and breakpoint editor.  
- [ ] Integrate domain registrar API and implement DNS validation flow.  
- [ ] Configure CI/CD pipelines (GitHub Actions).  
- [ ] Set up monitoring and error tracking (Sentry, Prometheus).  
- [ ] Run security and compliance checklist.

---

**End of prd.md**
