package com.droidhost.domain

enum class CatalogueCategory(val displayName: String) {
    ALL("All"),
    PANELS("Control Panels & PaaS"),
    WEB("Web & CMS"),
    DATABASE("Databases & Cache"),
    MONITORING("Monitoring & Tools"),
    GIT_COMPOSE("Git & Compose")
}

data class CatalogueItem(
    val id: String,
    val name: String,
    val category: CatalogueCategory,
    val description: String,
    val image: String,
    val port: Int? = null,
    val installScript: String,
    val defaultEnv: Map<String, String> = emptyMap(),
    val isPanel: Boolean = false,
    val docUrl: String = ""
)

object CatalogueRepository {
    val items: List<CatalogueItem> = listOf(
        // Control Panels & PaaS
        CatalogueItem(
            id = "dokploy",
            name = "Dokploy",
            category = CatalogueCategory.PANELS,
            description = "Next-generation self-hosted PaaS. Effortlessly deploy applications, databases, and Docker compose stacks with an intuitive UI.",
            image = "dokploy/dokploy:latest",
            port = 3000,
            installScript = "curl -sSL https://dokploy.com/setup.sh | (command -v bash >/dev/null 2>&1 && bash || sh)",
            isPanel = true,
            docUrl = "https://dokploy.com"
        ),
        CatalogueItem(
            id = "coolify",
            name = "Coolify",
            category = CatalogueCategory.PANELS,
            description = "All-in-one self-hosting PaaS alternative to Heroku & Netlify. Manage servers, applications, and databases with automatic SSL and Git deployments.",
            image = "ghcr.io/coollabsio/coolify:latest",
            port = 8000,
            installScript = "curl -fsSL https://cdn.coollabs.io/coolify/install.sh | (command -v bash >/dev/null 2>&1 && bash || sh)",
            isPanel = true,
            docUrl = "https://coolify.io"
        ),
        CatalogueItem(
            id = "portainer",
            name = "Portainer CE",
            category = CatalogueCategory.PANELS,
            description = "Lightweight management UI that allows you to easily manage your Docker hosts, Swarm clusters, containers, images, volumes, and networks.",
            image = "portainer/portainer-ce:latest",
            port = 9000,
            installScript = "docker run -d -p 9000:9000 --name portainer --restart=always -v /var/run/docker.sock:/var/run/docker.sock -v portainer_data:/data portainer/portainer-ce:latest",
            isPanel = true,
            docUrl = "https://www.portainer.io"
        ),
        CatalogueItem(
            id = "cockpit",
            name = "Cockpit Web Console",
            category = CatalogueCategory.PANELS,
            description = "Interactive server admin interface like cPanel. Manage storage, networking, user accounts, and system services in browser.",
            image = "cockpit/ws:latest",
            port = 9090,
            installScript = "docker run -d --name cockpit-panel -p 9090:9090 -v /:/host cockpit/ws:latest",
            isPanel = true,
            docUrl = "https://cockpit-project.org"
        ),

        // Web, CMS & Stacks
        CatalogueItem(
            id = "wordpress",
            name = "WordPress",
            category = CatalogueCategory.WEB,
            description = "The world's most popular open-source content management system and blogging platform.",
            image = "wordpress:alpine",
            port = 8081,
            installScript = "docker run -d --name wordpress -p 8081:80 -e WORDPRESS_DB_PASSWORD=droidhost wordpress:alpine",
            docUrl = "https://wordpress.org"
        ),
        CatalogueItem(
            id = "nginx",
            name = "Nginx Web Server",
            category = CatalogueCategory.WEB,
            description = "High-performance, lightweight HTTP web server and reverse proxy with Alpine Linux footprint.",
            image = "nginx:alpine",
            port = 8080,
            installScript = "docker run -d --name droid-web -p 8080:80 nginx:alpine",
            docUrl = "https://nginx.org"
        ),
        CatalogueItem(
            id = "node",
            name = "Node.js Server",
            category = CatalogueCategory.WEB,
            description = "JavaScript runtime environment for building scalable network applications and REST APIs.",
            image = "node:20-alpine",
            port = 3001,
            installScript = "docker run -d --name node-server -p 3001:3000 node:20-alpine",
            docUrl = "https://nodejs.org"
        ),

        // Databases & Cache
        CatalogueItem(
            id = "postgres",
            name = "PostgreSQL 16",
            category = CatalogueCategory.DATABASE,
            description = "Powerful, open-source object-relational database system with strong reputation for reliability and data integrity.",
            image = "postgres:16-alpine",
            port = 5432,
            installScript = "docker run -d --name pg-database -p 5432:5432 -e POSTGRES_PASSWORD=droidhost postgres:16-alpine",
            docUrl = "https://www.postgresql.org"
        ),
        CatalogueItem(
            id = "redis",
            name = "Redis 7",
            category = CatalogueCategory.DATABASE,
            description = "In-memory data structure store used as a database, cache, streaming engine, and message broker.",
            image = "redis:7-alpine",
            port = 6379,
            installScript = "docker run -d --name redis-cache -p 6379:6379 redis:7-alpine",
            docUrl = "https://redis.io"
        ),

        // Monitoring & Ingress
        CatalogueItem(
            id = "uptime-kuma",
            name = "Uptime Kuma",
            category = CatalogueCategory.MONITORING,
            description = "Fancy self-hosted monitoring tool. Monitor HTTP/TCP/DNS endpoints with status pages and alerting.",
            image = "louislam/uptime-kuma:1",
            port = 3002,
            installScript = "docker run -d --restart=always -p 3002:3001 -v uptime-kuma:/app/data --name uptime-kuma louislam/uptime-kuma:1",
            docUrl = "https://github.com/louislam/uptime-kuma"
        ),
        CatalogueItem(
            id = "cloudflared",
            name = "Cloudflare Tunnel",
            category = CatalogueCategory.MONITORING,
            description = "Outbound tunnel daemon exposing local web services to your custom domain securely without port forwarding.",
            image = "cloudflare/cloudflared:latest",
            port = null,
            installScript = "cloudflared tunnel run",
            docUrl = "https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/"
        ),

        // Git & Compose
        CatalogueItem(
            id = "git-deploy",
            name = "Git Direct Deploy",
            category = CatalogueCategory.GIT_COMPOSE,
            description = "Clone any public or private Git repository directly into the ARM64 Linux VM and start your application.",
            image = "git:alpine",
            port = null,
            installScript = "git clone <repo_url> && cd <repo_name>",
            docUrl = "https://git-scm.com"
        ),
        CatalogueItem(
            id = "docker-compose",
            name = "Docker Compose Stack",
            category = CatalogueCategory.GIT_COMPOSE,
            description = "Deploy multi-container stacks using standard docker-compose.yml files directly on the server.",
            image = "docker/compose:latest",
            port = null,
            installScript = "docker compose up -d",
            docUrl = "https://docs.docker.com/compose/"
        )
    )
}
