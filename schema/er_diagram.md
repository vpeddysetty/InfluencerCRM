# Entity Relationship Diagram

This ER diagram is based on the schema defined in [schema/influencer_crm_schema.sql](influencer_crm_schema.sql).

```mermaid
erDiagram
    USERS ||--o{ CREATORS : owns
    USERS ||--o{ CAMPAIGNS : owns
    USERS ||--o{ IMPORT_BATCHES : uploads
    USERS ||--o{ CAMPAIGN_CREATORS : manages
    USERS ||--o{ CAMPAIGN_TYPE_WORKFLOW_STAGES : defines
    USERS ||--o{ INTERACTIONS : records
    USERS ||--o{ MAPPING_EXAMPLES : curates
    USERS ||--o{ CREATOR_WORKFLOW_TASKS : assigns
    USERS ||--o{ CREATOR_WORKFLOW_APPROVALS : reviews
    USERS ||--o{ CREATOR_WORKFLOW_PAYMENTS : pays
    USERS ||--o{ CREATOR_WORKFLOW_EVENTS : audits
    USERS ||--o{ INFLUENCER_CAMPAIGN_CODES : issues
    USERS ||--o{ INFLUENCER_SALE_ATTRIBUTIONS : tracks

    IMPORT_BATCHES ||--o{ CREATORS : imported_from
    IMPORT_BATCHES ||--o{ CAMPAIGN_CREATORS : source_import

    CAMPAIGNS ||--o{ CAMPAIGN_CREATORS : includes
    CAMPAIGNS ||--o{ INFLUENCER_CAMPAIGN_CODES : configured_for
    CREATORS ||--o{ CAMPAIGN_CREATORS : participates_in
    CREATORS ||--o{ INTERACTIONS : has
    CREATORS ||--o{ INFLUENCER_CAMPAIGN_CODES : receives
    CREATORS ||--o{ INFLUENCER_SALE_ATTRIBUTIONS : attributed_to
    CAMPAIGN_CREATORS ||--o{ CREATOR_WORKFLOW_TASKS : executes
    CAMPAIGN_CREATORS ||--o{ CREATOR_WORKFLOW_APPROVALS : reviews
    CAMPAIGN_CREATORS ||--o{ CREATOR_WORKFLOW_PAYMENTS : settles
    CAMPAIGN_CREATORS ||--o{ CREATOR_WORKFLOW_EVENTS : logs
    CAMPAIGN_CREATORS ||--o{ INFLUENCER_CAMPAIGN_CODES : links
    CAMPAIGN_CREATORS ||--o{ INFLUENCER_SALE_ATTRIBUTIONS : links
    INFLUENCER_CAMPAIGN_CODES ||--o{ INFLUENCER_SALE_ATTRIBUTIONS : attributes

    USERS {
        uuid id PK
        citext email UK
        text password_hash
        text brand_name
        jsonb custom_attributes
        user_role role
        text plan
        timestamptz created_at
        timestamptz updated_at
    }

    CREATORS {
        uuid id PK
        uuid user_id FK
        uuid import_batch_id FK
        text handle
        text name
        text email
        platform_type platform
        integer follower_count
        numeric engagement_rate
        text[] tags
        text notes
        jsonb custom_attributes
        timestamptz created_at
        timestamptz updated_at
    }

    CAMPAIGNS {
        uuid id PK
        uuid user_id FK
        text name
        text goal
        text product
        numeric budget
        date start_date
        date end_date
        campaign_status status
        text campaign_type
        jsonb custom_attributes
        timestamptz created_at
        timestamptz updated_at
    }

    IMPORT_BATCHES {
        uuid id PK
        uuid user_id FK
        text source_filename
        bytea source_file
        text hydration_status
        jsonb column_mapping
        integer row_count
        timestamptz created_at
    }

    CAMPAIGN_CREATORS {
        uuid id PK
        uuid user_id FK
        uuid campaign_id FK
        uuid creator_id FK
        uuid import_batch_id FK
        pipeline_stage stage
        text notes
        jsonb tags
        text discount_code
        text link
        numeric agreed_fee
        text post_url
        content_review_status content_review_status
        jsonb custom_attributes
        timestamptz created_at
        timestamptz updated_at
    }

    CAMPAIGN_TYPE_WORKFLOW_STAGES {
        uuid id PK
        uuid user_id FK
        text campaign_type
        pipeline_stage stage_key
        text stage_label
        integer position
        boolean is_active
        timestamptz created_at
        timestamptz updated_at
    }

    MAPPING_EXAMPLES {
        uuid id PK
        uuid user_id FK
        text template_name
        text source_signature
        text[] source_tab_names
        text[] source_columns
        jsonb sample_values_json
        jsonb mappings_json
        numeric quality_score
        integer usage_count
        boolean is_active
        vector signature_embedding
        timestamptz created_at
        timestamptz updated_at
    }

    INTERACTIONS {
        uuid id PK
        uuid user_id FK
        uuid creator_id FK
        interaction_type type
        text body
        timestamptz created_at
    }

    CREATOR_WORKFLOW_TASKS {
        uuid id PK
        uuid user_id FK
        uuid campaign_creator_id FK
        text title
        workflow_actor assignee_actor
        uuid assignee_creator_id FK
        workflow_task_status status
        timestamptz due_at
        timestamptz completed_at
        jsonb metadata
        timestamptz created_at
        timestamptz updated_at
    }

    CREATOR_WORKFLOW_APPROVALS {
        uuid id PK
        uuid user_id FK
        uuid campaign_creator_id FK
        integer review_round
        text submission_url
        workflow_actor submitted_by_actor
        approval_decision decision
        workflow_actor decided_by_actor
        timestamptz submitted_at
        timestamptz decided_at
        jsonb metadata
    }

    CREATOR_WORKFLOW_PAYMENTS {
        uuid id PK
        uuid user_id FK
        uuid campaign_creator_id FK
        numeric amount
        text currency
        payout_status status
        timestamptz scheduled_at
        timestamptz paid_at
        jsonb metadata
        timestamptz created_at
        timestamptz updated_at
    }

    CREATOR_WORKFLOW_EVENTS {
        uuid id PK
        uuid user_id FK
        uuid campaign_creator_id FK
        workflow_actor actor
        text event_type
        jsonb event_data
        timestamptz created_at
    }

    INFLUENCER_CAMPAIGN_CODES {
        uuid id PK
        uuid user_id FK
        uuid campaign_id FK
        uuid creator_id FK
        uuid campaign_creator_id FK
        text code
        text code_type
        text landing_url
        boolean is_active
        timestamptz starts_at
        timestamptz ends_at
        jsonb metadata
        timestamptz created_at
        timestamptz updated_at
    }

    INFLUENCER_SALE_ATTRIBUTIONS {
        uuid id PK
        uuid user_id FK
        uuid campaign_code_id FK
        uuid campaign_id FK
        uuid creator_id FK
        uuid campaign_creator_id FK
        attribution_platform platform
        attribution_status status
        text order_id
        numeric sale_amount
        numeric net_amount
        numeric commission_amount
        text currency
        timestamptz occurred_at
        timestamptz tracked_at
        jsonb raw_payload
        timestamptz created_at
        timestamptz updated_at
    }
```

## Notes

- Users are the top-level tenant owner for all records.
- Creators are owned by a user and may be imported from an import batch.
- Campaigns and creators are linked through the join table `campaign_creators` to track workflow stage, notes, fees, and links.
- Workflow setup is configured by campaign type in `campaign_type_workflow_stages`.
- Interactions store relationship memory such as notes, emails, or DMs attached to creators.
- Core entities include `custom_attributes` JSONB for unmapped import fields scoped by entity.
- Mapping examples are persisted for retrieval-augmented mapping reuse via pgvector similarity search.
- Attribution data ties influencer codes to tracked sales in `influencer_sale_attributions`.
