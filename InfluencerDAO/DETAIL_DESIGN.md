# InfluencerDAO — Detailed Design

## 1. Purpose

InfluencerDAO is a Spring Boot REST service that exposes CRUD operations against the PostgreSQL tables defined for the InfluencerCRM database. The service is designed to work with the existing schema in the repository and provide HTTP endpoints for managing core entities such as users, creators, campaigns, import batches, campaign-to-creator links, and interactions.

## 2. Scope

The initial implementation covers the following database entities:

- users
- creators
- campaigns
- import_batches
- campaign_creators
- interactions
- mapping_examples

## 3. Architecture Overview

The solution is organized as a standard layered Spring Boot application:

- Presentation layer: REST controllers
- Service layer: currently thin, with repositories directly used by controllers
- Persistence layer: Spring Data JPA repositories over PostgreSQL
- Database layer: existing PostgreSQL database running from the repository Docker Compose setup

### Runtime stack

- Java 17
- Spring Boot 3.2.5
- Spring Web
- Spring Data JPA
- PostgreSQL Driver
- Maven

## 4. Project Structure

```text
InfluencerDAO/
  pom.xml
  src/main/java/com/influencer/dao/
    InfluencerDaoApplication.java
    controller/
      UserController.java
      CreatorController.java
      CampaignController.java
      ImportBatchController.java
      CampaignCreatorController.java
      InteractionController.java
      MappingExampleController.java
    model/
      User.java
      Creator.java
      Campaign.java
      ImportBatch.java
      CampaignCreator.java
      Interaction.java
      MappingExample.java
    repository/
      UserRepository.java
      CreatorRepository.java
      CampaignRepository.java
      ImportBatchRepository.java
      CampaignCreatorRepository.java
      InteractionRepository.java
      MappingExampleRepository.java
    resources/
      application.properties
      keystore.p12
```

## 5. Data Model Mapping

The JPA entities map directly to the PostgreSQL tables from the schema.

### Entity summary

| Entity | Table | Key purpose |
| --- | --- | --- |
| User | users | Stores account and authentication-related metadata |
| Creator | creators | Represents influencers/creators owned by a user |
| Campaign | campaigns | Represents marketing campaigns owned by a user |
| ImportBatch | import_batches | Tracks uploaded import sheets and mapping metadata |
| CampaignCreator | campaign_creators | Links creators to campaigns and tracks stage/status info |
| Interaction | interactions | Stores notes/emails/DMs related to a creator |
| MappingExample | mapping_examples | Stores approved mapping templates and retrieval metadata |

## 6. API Design

All controllers expose standard REST CRUD endpoints.

### Common pattern

- GET /{resource}
- GET /{resource}/{id}
- POST /{resource}
- PUT /{resource}/{id}
- DELETE /{resource}/{id}

### Base endpoints

| Resource | Endpoint |
| --- | --- |
| Users | /users |
| Creators | /creators |
| Campaigns | /campaigns |
| Import batches | /import-batches |
| Campaign creators | /campaign-creators |
| Interactions | /interactions |
| Mapping examples | /mapping-examples |

### Example request/response shape

#### Create a user

Request:

```http
POST /users
Content-Type: application/json
```

Body:

```json
{
  "email": "brand@example.com",
  "passwordHash": "hashed_password_here",
  "brandName": "Example Brand",
  "role": "owner",
  "plan": "free"
}
```

Response:

```json
{
  "id": "<uuid>",
  "email": "brand@example.com",
  "passwordHash": "hashed_password_here",
  "brandName": "Example Brand",
  "role": "owner",
  "plan": "free",
  "createdAt": "2026-07-16T12:00:00Z",
  "updatedAt": "2026-07-16T12:00:00Z"
}
```

## 7. Persistence Design

### Repository layer

Each entity has a corresponding JpaRepository interface such as:

- UserRepository
- CreatorRepository
- CampaignRepository
- ImportBatchRepository
- CampaignCreatorRepository
- InteractionRepository
- MappingExampleRepository

These repositories provide built-in CRUD functionality and allow the controllers to interact with the database with minimal boilerplate.

### Database connection

The application is configured in application.properties to connect to the local PostgreSQL instance:

- Host: localhost
- Port: 5432
- Database: influencercrm_db
- Username: influencercrm_user
- Password: password

## 8. Security and TLS

For local development, the service is configured to run over HTTPS on port 8443 using a self-signed keystore file.

### Local HTTPS settings

- Port: 8443
- Keystore: classpath:keystore.p12
- Alias: influencerdao

This allows development and testing over TLS without needing a production certificate.

## 9. Configuration Notes

The main application properties file contains:

- datasource connection settings
- JPA settings
- server port and SSL configuration

## 10. Current Status

The implementation currently provides:

- Project generation
- JPA entity mapping to the existing schema
- CRUD REST endpoints for the main tables
- CRUD REST endpoints for mapping_examples retrieval memory records
- Local HTTPS startup support
- Connection to the existing PostgreSQL database

Additional schema alignment in this revision includes:

- `custom_attributes` JSONB support in users, creators, campaigns, and campaign_creators
- content review metadata fields in campaign_creators (`content_review_status`, review timestamps, notes, reviewer)

## 11. Next Enhancements

Possible future improvements include:

1. Introduce DTOs for request/response payloads
2. Add service layer abstractions between controllers and repositories
3. Add validation annotations on request bodies
4. Add Swagger/OpenAPI support
5. Add authentication and authorization
6. Add pagination and filtering for list endpoints
7. Add unit and integration tests

## 12. Run Instructions

From the InfluencerDAO directory:

```bash
mvn spring-boot:run
```

Base URL:

```text
https://localhost:8443
```

Example:

```bash
curl -k https://localhost:8443/users
```
