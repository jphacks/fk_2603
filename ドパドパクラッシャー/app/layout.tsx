import { Analytics } from '@vercel/analytics/next'
import type { Metadata, Viewport } from 'next'
import { Dela_Gothic_One, Noto_Sans_JP } from 'next/font/google'
import './globals.css'

const dela = Dela_Gothic_One({
  weight: '400',
  subsets: ['latin'],
  variable: '--font-dela',
  display: 'swap',
})

const noto = Noto_Sans_JP({
  subsets: ['latin'],
  weight: ['400', '500', '700', '900'],
  variable: '--font-noto',
  display: 'swap',
})

export const metadata: Metadata = {
  title: 'ドパドパクラッシャー | ベッドから出ろ。話はそれからだ。',
  description:
    'M5Stackの圧力センサーでベッド滞在を検知し、ベッドから出るまでスマホを制限する大学生向け目覚ましアプリ「ドパドパクラッシャー」のUIプロトタイプ。',
  generator: 'v0.app',
}

export const viewport: Viewport = {
  colorScheme: 'dark',
  themeColor: '#0a0a0a',
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="ja" className={`${dela.variable} ${noto.variable} bg-background`}>
      <body>
        {children}
        {process.env.NODE_ENV === 'production' && <Analytics />}
      </body>
    </html>
  )
}
