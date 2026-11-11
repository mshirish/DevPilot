# DevPilot

**Understand your codebase with AI.**

DevPilot is an AI-powered GitHub repository assistant that helps developers explore, understand, and navigate their codebases using natural language.

Instead of manually searching through multiple files to understand how a feature works, developers can connect their GitHub repositories and ask questions about their code.

The project is built using Spring Boot, Next.js, Spring AI, and PostgreSQL with pgvector.

## Features

- **GitHub Authentication:** Sign in securely using GitHub OAuth 2.0.
- **Repository Integration:** Connect and manage GitHub repositories.
- **AI-Powered Code Search:** Use natural language to find and understand relevant code.
- **Repository Indexing:** Process source files into searchable chunks and vector embeddings.
- **Context-Aware Answers:** Use Retrieval-Augmented Generation (RAG) to generate answers based on repository content.
- **Indexing Status:** Track repository indexing progress and status.

> **Development status:** DevPilot is under active development. GitHub authentication and repository management form the foundation of the application, while the RAG indexing and AI question-answering workflows are being developed.

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Next.js, React, TypeScript |
| Backend | Java 21, Spring Boot |
| Authentication | Spring Security, GitHub OAuth 2.0 |
| AI Integration | Spring AI (OpenAI-compatible API: Google Gemini by default, or OpenAI) |
| Database | PostgreSQL |
| Vector Database | pgvector |
| ORM | Spring Data JPA, Hibernate |
| Infrastructure | Docker, Docker Compose |
| Build Tool | Gradle |

## How It Works

DevPilot uses Retrieval-Augmented Generation (RAG) to provide answers based on the actual contents of a repository.

1. **Connect:** The user signs in with GitHub and selects a repository.
2. **Index:** Repository files are fetched, filtered, and divided into smaller code chunks.
3. **Embed:** Spring AI converts code chunks into vector embeddings.
4. **Store:** Embeddings and related metadata are stored in PostgreSQL using pgvector.
5. **Retrieve:** Relevant code chunks are retrieved when the user asks a question.
6. **Generate:** The AI model uses the retrieved context to produce an answer.

This approach helps ground AI-generated responses in the repository's source code.

## Getting Started

### Prerequisites

Make sure the following are installed:

- Git
- Docker Desktop
- Node.js and npm
- Java 21 (for running the backend outside Docker)

You will also need GitHub OAuth credentials and an AI API key: a free Google Gemini key from Google AI Studio (the default), or an OpenAI key (see `.env.example`).

### 1. Clone the Repository

```bash
git clone https://github.com/Aashutosh17/DevPilot.git
cd DevPilot
```

### 2. Configure Environment Variables

Create the required environment configuration using the project's application settings.

Configure the PostgreSQL connection, GitHub OAuth client credentials, AI API key (`OPENAI_API_KEY`), and any application security secrets required by the backend.

Keep credentials in local environment files or environment variables. Do not commit secrets to GitHub.

### 3. Start Docker Services

From the project root directory, run:

```bash
docker compose up --build
```

This builds and starts the services defined in the Docker Compose configuration.

To run the containers in the background:

```bash
docker compose up -d --build
```

### 4. Start the Frontend

Open another terminal:

```bash
cd client
npm install
npm run dev
```

Open your browser and visit:

**http://localhost:3000**

If port 3000 is already occupied, Next.js may select another available port.

### 5. Stop the Application

To stop the Docker services:

```bash
docker compose down
```

## Deployment

DevPilot can be deployed for free as a public demo (Vercel + Render + Neon), with a guest
"Try the live demo" mode limited to preset repositories. See **[DEPLOY.md](DEPLOY.md)**.

## Project Structure

```text
DevPilot/
├── backend/
│   ├── src/main/java/
│   │   └── devPilot/backend/
│   │       ├── controller/
│   │       ├── dto/
│   │       ├── entity/
│   │       ├── exceptions/
│   │       ├── repository/
│   │       ├── security/
│   │       └── services/
│   │           ├── ai/
│   │           ├── github/
│   │           └── indexing/
│   ├── src/main/resources/
│   └── build.gradle
│
├── client/
│   ├── app/
│   ├── components/
│   └── package.json
│
├── docker-compose.yml
└── README.md
```

The backend handles authentication, repository operations, indexing, and AI integration. The frontend provides the user interface for interacting with repositories and AI-powered features.

## Development Roadmap

- [x] Initial frontend and backend setup
- [x] Docker Compose configuration
- [x] PostgreSQL and JPA integration
- [x] GitHub OAuth authentication
- [x] Repository management foundation
- [ ] Complete repository indexing pipeline
- [ ] Integrate semantic search with pgvector
- [ ] Implement repository-aware AI chat
- [ ] Add indexing progress and error handling
- [ ] End-to-end testing and deployment

## Contributors

- [Aashutosh Paneru](https://github.com/Aashutosh17)
- [Shirish Maharjan](https://github.com/mshirish)

## About

DevPilot is being developed as a Computer Science capstone project at Clark University.

The project explores how AI-assisted software engineering tools can make large codebases easier to understand and navigate.
