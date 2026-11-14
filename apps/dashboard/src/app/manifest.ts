import type { MetadataRoute } from 'next';

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'ANB Hosting',
    short_name: 'ANB Hosting',
    description: 'Manage websites on your local hosting network.',
    start_url: '/',
    display: 'standalone',
    background_color: '#f5f6fa',
    theme_color: '#635bff',
    icons: [
      {
        src: '/icon.svg',
        sizes: 'any',
        type: 'image/svg+xml',
        purpose: 'any',
      },
    ],
  };
}
