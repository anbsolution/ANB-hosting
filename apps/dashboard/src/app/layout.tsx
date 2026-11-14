import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import { PwaRegister } from '../components/pwa-register';
import './globals.css';

export const metadata: Metadata = {
  title: 'ANB Hosting | Control Panel',
  description: 'Manage your websites and hosting workspace.',
  manifest: '/manifest.webmanifest',
};

export const viewport: Viewport = {
  themeColor: '#635bff',
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <PwaRegister />
        {children}
      </body>
    </html>
  );
}
