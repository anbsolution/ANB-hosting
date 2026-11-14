'use client';

import { useEffect } from 'react';

export function PwaRegister() {
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;

    void navigator.serviceWorker
      .register('/sw.js', { scope: '/' })
      .catch((error: unknown) => {
        console.error('Unable to register offline app support', error);
      });
  }, []);

  return null;
}
