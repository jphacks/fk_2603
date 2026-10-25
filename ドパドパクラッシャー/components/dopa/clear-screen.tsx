'use client'

import { useEffect, useState } from 'react'
import { Flame, Timer, TrendingUp } from 'lucide-react'
import { cn } from '@/lib/utils'
import { formatDuration, type WakeResult } from './types'
import { Marquee, Sticker, Watcher } from './primitives'

export function ClearScreen({
  result,
  streak,
  level,
  xpBefore,
  onDone,
}: {
  result: WakeResult
  streak: number
  level: number
  xpBefore: number
  onDone: () => void
}) {
  const [filled, setFilled] = useState(false)

  useEffect(() => {
    const t = setTimeout(() => setFilled(true), 500)
    return () => clearTimeout(t)
  }, [])

  const before = xpBefore % 100
  const afterRaw = before + result.xp
  const leveledUp = afterRaw >= 100
  const after = leveledUp ? 100 : afterRaw

  return (
    <div className="flex h-full flex-col overflow-y-auto scrollbar-none bg-background">
      <Marquee text="MISSION CLEAR ドパ解放" className="bg-lime py-1.5 text-lime-foreground" />

      <div className="relative flex flex-col items-center px-5 pt-6 text-center">
        <p className="font-display text-[44px] leading-[0.95] text-outline">MISSION</p>
        <p className="animate-stamp font-display text-[56px] leading-[0.95] text-lime">CLEAR!!</p>

        <div className="relative mt-2 w-full max-w-[240px]">
          <Watcher mood="clear" className="aspect-square w-full animate-rise" priority />
          <div
            className="absolute -right-4 top-4 flex size-20 animate-stamp items-center justify-center rounded-full border-4 border-lime bg-background [animation-delay:250ms]"
            aria-label={`ランク ${result.rank}`}
          >
            <span className="font-display text-5xl leading-none text-lime">{result.rank}</span>
          </div>
          <p className="absolute -left-2 bottom-6 -rotate-6 rounded-xl rounded-bl-none bg-foreground px-3 py-1.5 text-xs font-bold text-background">
            {'…まあ、やるじゃん。'}
          </p>
        </div>
      </div>

      <dl className="mx-5 mt-4 grid grid-cols-3 gap-2">
        {[
          { icon: Timer, label: '離床タイム', value: formatDuration(result.seconds) },
          { icon: Flame, label: '連続起床', value: `${streak}日` },
          { icon: TrendingUp, label: '獲得XP', value: `+${result.xp}` },
        ].map(({ icon: Icon, label, value }, i) => (
          <div
            key={label}
            className="animate-rise rounded-2xl border bg-surface p-3"
            style={{ animationDelay: `${300 + i * 100}ms` }}
          >
            <Icon className="size-4 text-lime" aria-hidden="true" />
            <dt className="mt-2 text-[10px] text-muted-foreground">{label}</dt>
            <dd className="font-display text-lg tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>

      <div className="mx-5 mt-3 rounded-2xl border bg-surface p-4">
        <div className="flex items-center justify-between">
          <span className="font-display text-sm">LV.{level}</span>
          {leveledUp && filled && <Sticker className="animate-stamp">LEVEL UP!</Sticker>}
        </div>
        <div className="mt-2 h-3 overflow-hidden rounded-full bg-surface-2">
          <div
            className={cn('h-full rounded-full bg-lime transition-[width] duration-1000 ease-out')}
            style={{ width: `${filled ? after : before}%` }}
          />
        </div>
        <p className="mt-2 text-[11px] text-muted-foreground">
          {leveledUp ? '新しい称号「布団スレイヤー」を獲得' : `次のレベルまで ${100 - afterRaw} XP`}
        </p>
      </div>

      <div className="mt-auto px-5 pb-8 pt-6">
        <button
          type="button"
          onClick={onDone}
          className="w-full rounded-2xl bg-lime py-5 font-display text-lg text-lime-foreground shadow-[0_6px_0_0_#6f8f00] transition active:translate-y-1 active:shadow-[0_2px_0_0_#6f8f00]"
        >
          スマホを解放する
        </button>
      </div>
    </div>
  )
}
