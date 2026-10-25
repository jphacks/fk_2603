'use client'

import { useEffect, useRef, useState } from 'react'
import { Camera, Clapperboard, Gamepad2, Lock, MessageCircle, Music2, Play } from 'lucide-react'
import { cn } from '@/lib/utils'
import { formatDuration } from './types'
import { Sticker, Watcher } from './primitives'

const lockedApps = [
  { name: 'ショート', icon: Clapperboard },
  { name: 'SNS', icon: MessageCircle },
  { name: '写真SNS', icon: Camera },
  { name: '動画', icon: Play },
  { name: 'ゲーム', icon: Gamepad2 },
  { name: '音楽', icon: Music2, allowed: true },
]

const taunts = [
  'あと5分、は嘘。',
  'その布団、もう温まりきってる。',
  'タイムラインは逃げない。',
  '1限、始まるぞ。',
  'ドーパミン、まだお預け。',
]

const THRESHOLD = 30

export function LockScreen({ onLeaveBed }: { onLeaveBed: (seconds: number) => void }) {
  const [elapsed, setElapsed] = useState(0)
  const [pressure, setPressure] = useState(82)
  const [leaving, setLeaving] = useState(false)
  const [tauntIndex, setTauntIndex] = useState(0)
  const elapsedRef = useRef(0)

  useEffect(() => {
    const id = setInterval(() => {
      elapsedRef.current += 1
      setElapsed(elapsedRef.current)
      if (elapsedRef.current % 4 === 0) setTauntIndex((i) => (i + 1) % taunts.length)
    }, 1000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    if (leaving) {
      const id = setInterval(() => {
        setPressure((p) => {
          const next = Math.max(0, p - 7)
          if (next === 0) clearInterval(id)
          return next
        })
      }, 90)
      return () => clearInterval(id)
    }
    const id = setInterval(() => {
      setPressure(76 + Math.round(Math.random() * 12))
    }, 700)
    return () => clearInterval(id)
  }, [leaving])

  useEffect(() => {
    if (leaving && pressure === 0) {
      const t = setTimeout(() => onLeaveBed(elapsedRef.current + 134), 450)
      return () => clearTimeout(t)
    }
  }, [leaving, pressure, onLeaveBed])

  const inBed = pressure >= THRESHOLD

  return (
    <div className="flex h-full flex-col overflow-y-auto scrollbar-none bg-background bg-grid px-5 pb-8">
      <div className="flex items-center justify-between pt-2">
        <Sticker tone={inBed ? 'alert' : 'lime'}>
          <Lock className="size-3" aria-hidden="true" />
          {inBed ? 'LOCKED' : 'UNLOCKING'}
        </Sticker>
        <span className="text-[11px] font-bold text-muted-foreground">M5Stack 接続中</span>
      </div>

      <div className="mt-5 text-center">
        <p className="text-xs font-bold tracking-widest text-muted-foreground">ベッド滞在時間</p>
        <p className="font-display text-[72px] leading-none tabular-nums" aria-live="off">
          {formatDuration(elapsed + 134)}
        </p>
        <p className="mt-2 font-display text-2xl leading-tight">
          ベッドから出ろ。
          <br />
          <span className="text-lime">話はそれからだ。</span>
        </p>
      </div>

      {/* Pressure gauge */}
      <div className="mt-6 rounded-3xl border bg-surface p-4">
        <div className="flex items-center justify-between text-[11px] font-bold">
          <span className="text-muted-foreground">PRESSURE SENSOR</span>
          <span className={cn('tabular-nums', inBed ? 'text-alert' : 'text-lime')}>{pressure} / 100</span>
        </div>
        <div
          className="relative mt-3 h-4 overflow-hidden rounded-full bg-surface-2"
          role="meter"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pressure}
          aria-label="ベッドの圧力"
        >
          <div
            className={cn(
              'h-full rounded-full transition-all duration-300',
              inBed ? 'bg-alert' : 'bg-lime',
            )}
            style={{ width: `${pressure}%` }}
          />
          <span
            className="absolute inset-y-0 w-0.5 bg-foreground"
            style={{ left: `${THRESHOLD}%` }}
            aria-hidden="true"
          />
        </div>
        <p className="mt-2 text-[11px] text-muted-foreground">
          {inBed ? `しきい値 ${THRESHOLD} を下回ると制限解除` : '離床を検知。制限を解除しています…'}
        </p>
      </div>

      {/* Locked apps */}
      <ul className="mt-4 grid grid-cols-6 gap-2" aria-label="制限中のアプリ">
        {lockedApps.map(({ name, icon: Icon, allowed }) => {
          const locked = inBed && !allowed
          return (
            <li key={name} className="flex flex-col items-center gap-1">
              <span
                className={cn(
                  'relative flex size-11 items-center justify-center rounded-2xl transition',
                  locked ? 'bg-surface-2 text-muted-foreground' : 'bg-lime text-lime-foreground',
                )}
              >
                <Icon className="size-5" aria-hidden="true" />
                {locked && (
                  <span className="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-alert">
                    <Lock className="size-2.5 text-foreground" aria-hidden="true" />
                  </span>
                )}
              </span>
              <span className="text-[9px] font-medium text-muted-foreground">
                {name}
                <span className="sr-only">{locked ? '（制限中）' : '（利用可）'}</span>
              </span>
            </li>
          )
        })}
      </ul>

      <div className="mt-4 flex items-center gap-3 rounded-3xl border bg-surface p-3">
        <Watcher mood="sleepy" className="size-16 shrink-0 rounded-2xl" />
        <p className="text-sm font-bold" aria-live="polite">
          {taunts[tauntIndex]}
        </p>
      </div>

      <div className="mt-auto pt-6">
        <button
          type="button"
          disabled={leaving}
          onClick={() => setLeaving(true)}
          className="w-full rounded-2xl bg-foreground py-4 font-display text-base text-background transition active:scale-[0.98] disabled:opacity-60"
        >
          {leaving ? 'センサー検知中…' : 'ベッドから出る'}
        </button>
        <p className="mt-2 text-center text-[10px] text-muted-foreground">
          デモ：圧力センサーの離床検知をシミュレート｜緊急通話は常に利用可
        </p>
      </div>
    </div>
  )
}
