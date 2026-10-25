import Image from 'next/image'
import { cn } from '@/lib/utils'

type WatcherMood = 'sleepy' | 'angry' | 'clear'

const watcherSrc: Record<WatcherMood, string> = {
  sleepy: '/images/dopa-watcher.png',
  angry: '/images/dopa-watcher-angry.png',
  clear: '/images/dopa-watcher-clear.png',
}

export function Watcher({
  mood = 'sleepy',
  className,
  priority,
}: {
  mood?: WatcherMood
  className?: string
  priority?: boolean
}) {
  return (
    <div className={cn('relative overflow-hidden', className)}>
      <Image
        src={watcherSrc[mood]}
        alt="ドパ監視員"
        fill
        priority={priority}
        sizes="(max-width: 480px) 80vw, 360px"
        className="object-cover mix-blend-lighten"
      />
    </div>
  )
}

export function Sticker({
  children,
  className,
  tone = 'lime',
}: {
  children: React.ReactNode
  className?: string
  tone?: 'lime' | 'white' | 'dark' | 'alert'
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md border-2 border-background px-2 py-0.5 font-display text-[11px] leading-tight tracking-wide shadow-[3px_3px_0_0_rgb(0_0_0_/_0.6)]',
        tone === 'lime' && 'bg-lime text-lime-foreground',
        tone === 'white' && 'bg-foreground text-background',
        tone === 'dark' && 'border-foreground/80 bg-background text-foreground',
        tone === 'alert' && 'bg-alert text-foreground',
        className,
      )}
    >
      {children}
    </span>
  )
}

export function SectionLabel({ children, right }: { children: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="flex items-end justify-between">
      <h2 className="font-display text-sm tracking-wider text-muted-foreground">{children}</h2>
      {right}
    </div>
  )
}

export function Card({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('rounded-3xl border bg-surface p-4', className)}>{children}</div>
}

export function Marquee({ text, className }: { text: string; className?: string }) {
  const items = Array.from({ length: 6 }, () => text)
  return (
    <div className={cn('shrink-0 overflow-hidden whitespace-nowrap', className)} aria-hidden="true">
  <div className="flex w-max animate-marquee items-center">
  {[...items, ...items].map((t, i) => (
  <span key={i} className="block px-3 py-0.5 font-display text-xs leading-normal tracking-widest">
            {t} ✕
          </span>
        ))}
      </div>
    </div>
  )
}

export function StatusBar({ dark = false }: { dark?: boolean }) {
  return (
    <div
      className={cn(
        'flex h-11 shrink-0 items-center justify-between px-7 pt-1 text-[13px] font-bold',
        dark ? 'text-lime-foreground' : 'text-foreground',
      )}
      aria-hidden="true"
    >
      <span>7:30</span>
      <div className="flex items-center gap-1.5">
        <span className="flex items-end gap-[2px]">
          {[4, 6, 8, 10].map((h) => (
            <span key={h} className="w-[3px] rounded-sm bg-current" style={{ height: h }} />
          ))}
        </span>
        <span className="ml-1 flex h-[11px] w-[22px] items-center rounded-[3px] border border-current p-[1px]">
          <span className="h-full w-[70%] rounded-[1px] bg-current" />
        </span>
      </div>
    </div>
  )
}
