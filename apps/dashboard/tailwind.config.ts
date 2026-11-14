import type { Config } from 'tailwindcss';

const config: Config = {
  darkMode: 'class',
  content: ['./src/**/*.{js,ts,jsx,tsx,mdx}'],
  theme: {
    extend: {
      colors: {
        ink: '#101828',
        muted: '#667085',
        brand: '#635bff',
        canvas: '#f5f6fa',
      },
      boxShadow: {
        soft: '0 10px 30px rgba(16, 24, 40, 0.06)',
      },
    },
  },
  plugins: [],
};

export default config;
