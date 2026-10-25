'use client'

import { useState } from 'react'
import { Battery, Bluetooth, Cpu, RefreshCw } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Card, SectionLabel, Sticker } from './primitives'

const sparkline = [12, 14, 13, 80, 84, 82, 86, 83, 85, 81, 84, 30, 8, 6, 9, 7]

const restrictionDefaults = [
  { id: 'short', label: 'ショート動画', sub: '縦スワイプ系すべて', on: true },
  { id: 'sns', label: 'SNS', sub: 'タイムライン・ストーリー', on: true },
  { id: 'video', label: '動画アプリ', sub: '長尺動画', on: true },
  { id: 'game', label: 'ゲーム', sub: 'デイリーボーナス含む', on: false },
]

export function DeviceScreen() {
  const [threshold, setThreshold] = useState(30)
  const [grace, setGrace] = useState(60)
  const [restrictions, setRestrictions] = useState(restrictionDefaults)

  const points = sparkline
    .map((v, i) => `${(i / (sparkline.length - 1)) * 100},${100 - v}`)
    .join(' ')

  return (
    <div className="flex flex-col gap-5 px-5 pb-6">
      <header className="pt-1">
        <p className="text-xs font-medium text-muted-foreground">DEVICE</p>
        <h1 className="font-display text-2xl">センサー設定</h1>
      </header>

      <Card className="p-5">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <span className="flex size-12 items-center justify-center rounded-2xl bg-lime text-lime-foreground">
              <Cpu className="size-6" aria-hidden="true" />
            </span>
            <div>
              <p className="whitespace-nowrap font-display text-sm">M5Stack Core2</p>
              <p className="text-[11px] text-muted-foreground">+ FSR圧力センサー ×2</p>
            </div>
          </div>
          <Sticker>ONLINE</Sticker>
        </div>
        <div className="mt-4 grid grid-cols-3 gap-2 text-center">
          {[
            { icon: Bluetooth, label: 'BLE', value: '-48dBm' },
            { icon: Battery, label: '電池', value: '86%' },
            { icon: RefreshCw, label: '同期', value: '2秒前' },
          ].map(({ icon: Icon, label, value }) => (
            <div key={label} className="rounded-xl bg-surface-2 px-2 py-2.5">
              <Icon className="mx-auto size-4 text-lime" aria-hidden="true" />
              <p className="mt-1 text-[10px] text-muted-foreground">{label}</p>
              <p className="text-xs font-bold">{value}</p>
            </div>
          ))}
        </div>
      </Card>

      <section aria-labelledby="live" className="flex flex-col gap-3">
        <SectionLabel right={<span className="text-[11px] text-muted-foreground">直近8時間</span>}>
          <span id="live">PRESSURE LOG</span>
        </SectionLabel>
        <Card>
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-28 w-full" role="img" aria-label="圧力センサーの推移グラフ。深夜に就寝、朝7時半に離床">
            <line x1="0" x2="100" y1={100 - threshold} y2={100 - threshold} stroke="var(--foreground)" strokeOpacity="0.4" strokeDasharray="2 2" strokeWidth="0.6" vectorEffect="non-scaling-stroke" />
            <polygon points={`0,100 ${points} 100,100`} fill="var(--lime)" fillOpacity="0.12" />
            <polyline points={points} fill="none" stroke="var(--lime)" strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
          </svg>
          <div className="mt-2 flex justify-between text-[10px] text-muted-foreground">
            <span>0:00</span>
            <span>就寝 1:12</span>
            <span>離床 7:32</span>
            <span>8:00</span>
          </div>
        </Card>
      </section>

      <section aria-labelledby="tuning" className="flex flex-col gap-3">
        <SectionLabel>
          <span id="tuning">判定チューニング</span>
        </SectionLabel>
        <Card className="flex flex-col gap-5">
          <RangeField
            id="threshold"
            label="在床しきい値"
            hint="これより重いと「ベッドにいる」判定"
            value={threshold}
            min={10}
            max={80}
            suffix=""
            onChange={setThreshold}
          />
          <RangeField
            id="grace"
            label="離床確認時間"
            hint="この秒数ベッドから離れたら解除"
            value={grace}
            min={10}
            max={180}
            suffix="秒"
            onChange={setGrace}
          />
        </Card>
      </section>

      <section aria-labelledby="restrict" className="flex flex-col gap-3">
        <SectionLabel>
          <span id="restrict">在床中に封印するもの</span>
        </SectionLabel>
        <ul className="flex flex-col divide-y overflow-hidden rounded-3xl border bg-surface">
          {restrictions.map((r) => (
            <li key={r.id} className="flex items-center justify-between px-4 py-3.5">
              <div>
                <p className="text-sm font-bold">{r.label}</p>
                <p className="text-[11px] text-muted-foreground">{r.sub}</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={r.on}
                aria-label={r.label}
                onClick={() =>
                  setRestrictions((prev) => prev.map((x) => (x.id === r.id ? { ...x, on: !x.on } : x)))
                }
                className={cn('relative h-7 w-12 rounded-full transition-colors', r.on ? 'bg-lime' : 'bg-surface-2')}
              >
                <span
                  className={cn(
                    'absolute top-1 size-5 rounded-full transition-all',
                    r.on ? 'left-6 bg-lime-foreground' : 'left-1 bg-muted-foreground',
                  )}
                />
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

function RangeField({
  id,
  label,
  hint,
  value,
  min,
  max,
  suffix,
  onChange,
}: {
  id: string
  label: string
  hint: string
  value: number
  min: number
  max: number
  suffix: string
  onChange: (v: number) => void
}) {
  return (
    <div>
      <div className="flex items-end justify-between">
        <label htmlFor={id} className="text-sm font-bold">
          {label}
        </label>
        <span className="font-display text-xl tabular-nums text-lime">
          {value}
          <span className="text-xs">{suffix}</span>
        </span>
      </div>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-2 h-2 w-full cursor-pointer appearance-none rounded-full bg-surface-2 accent-lime"
      />
      <p className="mt-1 text-[11px] text-muted-foreground">{hint}</p>
    </div>
  )
}
