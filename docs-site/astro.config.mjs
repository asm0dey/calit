// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://asm0dey.github.io',
  base: '/calit/',
  integrations: [
    starlight({
      title: 'calit',
      description: 'Self-hosted, multi-user scheduling you actually own.',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/asm0dey/calit' },
      ],
      customCss: ['./src/styles/custom.css'],
      head: [
        {
          tag: 'link',
          attrs: { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        },
        {
          tag: 'link',
          attrs: { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: true },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'stylesheet',
            href: 'https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,400..700;1,9..144,400..600&family=Hanken+Grotesk:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap',
          },
        },
      ],
      sidebar: [
        {
          label: 'Introduction',
          items: [
            { label: 'What is calit', slug: 'introduction/what-is-calit' },
            { label: 'Quick start', slug: 'introduction/quick-start' },
          ],
        },
        {
          label: 'Installation',
          items: [
            { label: 'Docker Compose', slug: 'installation/docker-compose' },
            { label: 'Configuration', slug: 'installation/configuration' },
            {
              label: 'Reverse proxy',
              items: [
                { label: 'Overview', slug: 'installation/reverse-proxy/overview' },
                { label: 'Nginx Proxy Manager', slug: 'installation/reverse-proxy/nginx-proxy-manager' },
                { label: 'nginx', slug: 'installation/reverse-proxy/nginx' },
                { label: 'Caddy', slug: 'installation/reverse-proxy/caddy' },
                { label: 'Traefik', slug: 'installation/reverse-proxy/traefik' },
              ],
            },
            { label: 'Google OAuth setup', slug: 'installation/google-oauth' },
            { label: 'Cloudflare Turnstile setup', slug: 'installation/turnstile' },
          ],
        },
        {
          label: 'Usage',
          items: [
            { label: 'First run & admin user', slug: 'usage/first-run' },
            { label: 'Resetting a password', slug: 'usage/password-reset' },
            { label: 'Meeting types', slug: 'usage/meeting-types' },
            { label: 'Availability & overrides', slug: 'usage/availability' },
            { label: 'Bookings & approvals', slug: 'usage/bookings' },
            { label: 'Users & admin', slug: 'usage/users-admin' },
          ],
        },
        {
          label: 'Releases',
          items: [
            { label: 'Changelog', slug: 'releases/changelog' },
            { label: 'Upgrading', slug: 'releases/upgrading' },
          ],
        },
      ],
    }),
  ],
});
