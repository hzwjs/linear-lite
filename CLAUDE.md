# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Linear Lite is a lightweight task/kanban application.
- **Frontend**: Vue 3, TypeScript, Vite.
- **Backend**: Spring Boot 3.x, MyBatis-Plus, MySQL 8.

## Development Commands

### Backend (linear-lite-server)
- **Run backend**: `cd linear-lite-server && mvn spring-boot:run`
- **Build backend**: `cd linear-lite-server && mvn clean package`
- **Database Initialization**:
  1. Create database: `mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS linear_lite DEFAULT CHARACTER SET utf8mb4;"`
  2. Initialize schema and seeds: `mysql -u root -p linear_lite < linear-lite-server/src/main/resources/schema.sql`
- **Environment Configuration**: Copy `.env.properties.example` to `.env.properties` in `linear-lite-server/` and configure database/R2 credentials.

### Frontend
- **Install dependencies**: `npm install`
- **Run dev server**: `npm run dev`
- **Build for production**: `npm run build`
  - Note: For single JAR deployment, do NOT set `VITE_API_BASE_URL` during build.

## Architecture & Structure

### Backend Structure (`linear-lite-server/`)
- `src/main/java/com/linearlite/server/`:
  - `common/`: Common utilities and response wrappers (`ApiResponse`).
  - `config/`: Configuration classes (e.g., CORS, WebConfig).
  - `controller/`: REST API endpoints.
  - `entity/`: JPA/MyBatis-Plus entities mapping to MySQL tables.
  - `exception/`: Global error handling.
  - `mapper/`: MyBatis-Plus mapper interfaces for SQL interaction.
  - `service/`: Business logic layer.
- `src/main/resources/`:
  - `application.yml`: Spring Boot configuration.
  - `schema.sql`: Database schema definition and seed data.

### Frontend Structure (`src/`)
- `components/`: Reusable Vue components.
- `views/`: Page-level components.
- `router/`: Vue Router configuration.
- `api/`: API client definitions (using Axios).
- `i18n/`: Internationalization (i18n) messages.
- `assets/`: Static assets.

## Key Information
- **Backend Port**: 9080 (default).
- **Frontend Port**: 5173 (default).
- **API Proxy**: Vite config proxies `/api` to `http://localhost:9080`.
- **Database**: MySQL 8.x with `utf8mb4` charset.
