# Brand Owner End-to-End Test Report

Date: 2026-07-21

## Summary

This report captures a successful end-to-end UI journey executed as a new brand owner in the running application. The journey covered sign-up, login, campaign creation, creator creation, workflow setup, work-item creation, work-item updates, and stage movement. During the run, several issues were found and fixed so the flow could complete successfully.

## Journey Data

Brand owner test data:

- Full name: Avery Stone
- Brand: Northwind Labs
- Email: avery.northwind.e2e.0721@example.com
- Password: Northwind!E2E#2026

Campaign test data:

- Name: Northwind Summer Serum Drop
- Initial budget: 25000
- Updated budget: 26500
- Status: Active
- Campaign type: Sponsored Content
- Custom attribute: product_line=GlowLab

Creator test data:

- Creator 1: Maya Chen, @mayachenbeauty, instagram, maya.chen.mock@example.com
- Creator 1 updated email: maya.chen.creator@example.com
- Creator 1 custom attribute: tier=hero
- Creator 2: Jordan Brooks, @jordanunboxed, tiktok, jordan.brooks.mock@example.com

Work-item test data:

- Jordan Brooks initial work item:
  - Stage: Outreach
  - Fee: 1800
  - Due date: 2026-08-05
  - Notes: Initial TikTok outreach with product seeding angle.
  - Tags: priority,tiktok
- Jordan Brooks updated work item:
  - Stage: Shipped
  - Fee: 1950
  - Due date: 2026-08-07
  - Notes: Initial TikTok outreach with product seeding angle. Follow-up scheduled after rate card review and creator availability confirmation.
  - Tags: priority,tiktok,followup
- Maya Chen work item:
  - Stage: Agreed
  - Fee: 2200
  - Due date: 2026-08-12
  - Notes: Confirmed Instagram deliverable plan and brief.
  - Initial tags: instagram,hero
  - Updated tags: instagram,hero,ugc

## Executed Journey

### 1. Sign up as a new brand owner

Path executed:

- Landing page `/`
- Filled sign-up form
- Submitted `Create workspace`

Result:

- Sign-up succeeded.
- Workspace loaded for the new brand owner.
- A new row was inserted into `users`.

### 2. Log out and log back in

Path executed:

- Logged out from workspace header
- Switched to login tab on `/`
- Logged back in using the newly created credentials

Result:

- Login succeeded.
- This confirmed both the sign-up and dedicated login paths.

### 3. Create a campaign

Path executed:

- Workspace navigation -> `Campaigns`
- Filled campaign form on `/campaigns`
- Added custom attribute
- Submitted `Add campaign`

Result:

- Campaign creation succeeded.
- A row was inserted into `campaigns`.

### 4. Update the campaign

Path executed:

- `/campaigns`
- Opened campaign edit drawer
- Updated budget from `25000` to `26500`
- Saved changes

Result:

- Campaign update succeeded.
- Existing `campaigns` row was updated.

### 5. Create two creators

Path executed:

- Workspace navigation -> `Creators`
- Filled creator form for Maya Chen and submitted
- Filled creator form for Jordan Brooks and submitted

Result:

- Both creator creates succeeded.
- Two rows were inserted into `creators`.

### 6. Update one creator

Path executed:

- `/creators`
- Opened Maya Chen edit drawer
- Updated email to `maya.chen.creator@example.com`
- Saved changes

Result:

- Creator update succeeded.
- Existing `creators` row was updated.

### 7. Configure workflow setup for campaign type

Path executed:

- Workspace navigation -> `Workflow`
- Selected campaign type `Sponsored Content`
- Saved workflow setup on `/workflow`

Result:

- Workflow setup save succeeded.
- Five rows were inserted into `campaign_type_workflow_stages` for the new user and campaign type.

### 8. Create creator-campaign work items

Path executed:

- `/workflow`
- Tied Jordan Brooks to the campaign with stage, fee, notes, due date, and tags
- Tied Maya Chen to the campaign with stage, fee, notes, due date, and tags

Result:

- Both work-item creates succeeded.
- Two rows were inserted into `campaign_creators`.

### 9. Update work items in the workflow board

Path executed:

- `/workflow`
- Used quick note editor on Jordan Brooks card
- Used quick tags editor on Maya Chen card
- Used drawer edit flow on Jordan Brooks card

Result:

- Quick note update succeeded.
- Quick tags update succeeded.
- Drawer update succeeded.
- Existing `campaign_creators` rows were updated.

### 10. Move a work item across stages

Path executed:

- `/workflow`
- Moved Jordan Brooks work item from `Outreach` to `Shipped`

Result:

- Stage transition succeeded.
- Existing `campaign_creators` row was updated.

## Issues Found And Fixed During Testing

### 1. Stale BFF process on port 18081

Symptom:

- Workflow-related BFF endpoints returned `404`.

Fix:

- Stopped the stale process and relaunched the current BFF build.

### 2. Stale DAO process on port 8443

Symptom:

- DAO did not expose current workflow endpoints.

Fix:

- Stopped the stale process and relaunched the current DAO build.

### 3. Creator tenant scoping bug

Symptom:

- `/api/creators` returned creators from other users inside the new brand-owner session.

Fix:

- DAO creator list path was updated to honor optional `userId` filtering.
- Repository support was added for `findByUserId(UUID userId)`.

### 4. Workflow card edit crash due to tag type mismatch

Symptom:

- Saving a work-item edit could leave `tags` as a string in optimistic state.
- Workflow board then crashed when code assumed `tags` was an array.

Fix:

- Normalized `tags` into arrays before optimistic assignment state updates.
- Hardened workflow page rendering and filtering to normalize tag input defensively.

## Database Impact

### Inserted rows

- `users`: 1
- `campaigns`: 1
- `creators`: 2
- `campaign_type_workflow_stages`: 5
- `campaign_creators`: 2

### Updated rows

- `campaigns`: 1
- `creators`: 1
- `campaign_creators`: 2

### Deleted rows

- None during the application journey

## Final Data State

### User

- Email: `avery.northwind.e2e.0721@example.com`
- Brand: `Northwind Labs`
- Role: `owner`

### Campaigns

- Northwind Summer Serum Drop
  - Budget: 26500
  - Status: active
  - Campaign type: sponsored content
  - Custom attributes: `{ "product_line": "GlowLab" }`

### Creators

- Maya Chen
  - Handle: `@mayachenbeauty`
  - Platform: `instagram`
  - Email: `maya.chen.creator@example.com`
  - Custom attributes: `{ "tier": "hero" }`
- Jordan Brooks
  - Handle: `@jordanunboxed`
  - Platform: `tiktok`
  - Email: `jordan.brooks.mock@example.com`

### Campaign creators / work items

- Jordan Brooks
  - Stage: `shipped`
  - Fee: 1950
  - Due date: 2026-08-07
  - Tags: `priority,tiktok,followup`
- Maya Chen
  - Stage: `agreed`
  - Fee: 2200
  - Due date: 2026-08-12
  - Tags: `instagram,hero,ugc`

### Workflow setup

Campaign type `sponsored content` saved with active stages:

- outreach
- agreed
- shipped
- posted
- paid

## Notes

- Workflow persistence for this test now uses stage-only creator-campaign records without swimlane storage.