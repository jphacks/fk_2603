'use client'

import { useState } from 'react'
import { AlarmClock, BedDouble, Flame, Plus, Zap, Trash2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Card, SectionLabel, Sticker, Watcher } from './primitives'
import { AlarmModal } from './alarm-modal'

type Alarm = { id: string; time: string; label: string; days: string; on: boolean }

const initialAlarms: Alarm[] = [
  { id: 'a1', time: '07:30', label: '1限あるやつ', days: '月 火 木', on: true },
  { id: 'a2', time: '09:00', label: '2限からの民', days: '水 金', on: true },
  { id: 'a3', time: '11:30', label: '休日（甘え）', days: '土 日', on: false },
]

export function HomeScreen({
  level,
  xp,
  streak,
  onRingAlarm,
}: {
  level: number
  xp: number
  streak: number
  onRingAlarm: () => void
}) {
  const [alarms, setAlarms] = useState(initialAlarms)

   // アラーム追加モーダルの開閉状態
  const [isAlarmModalOpen, setIsAlarmModalOpen] = useState(false)

  const deleteAlarm = (id: string) => {
    setAlarms((prev) => prev.filter((alarm) => alarm.id !== id))
  }

  return (
    <div className="flex flex-col gap-5 px-5 pb-6">
      <header className="flex items-center justify-between pt-1">
        <div>
          <p className="text-xs font-medium text-muted-foreground">おはよ、ドパガキ。</p>
          <h1 className="font-display text-2xl leading-tight">
            DOPA<span className="text-lime">DOPA</span>
          </h1>
        </div>
        <div className="flex items-center gap-2 rounded-full border bg-surface py-1 pl-1 pr-3">
          <span className="flex size-7 items-center justify-center rounded-full bg-lime font-display text-xs text-lime-foreground">
            {level}
          </span>
          <div className="flex flex-col">
            <span className="text-[10px] leading-none text-muted-foreground">LV</span>
            <span className="h-1 w-12 overflow-hidden rounded-full bg-surface-2">
              <span className="block h-full bg-lime" style={{ width: `${xp % 100}%` }} />
            </span>
          </div>
        </div>
      </header>

      {/* Next alarm hero */}
      <section
        aria-labelledby="next-alarm"
        className="relative overflow-hidden rounded-[28px] bg-lime p-5 text-lime-foreground"
      >
        <div className="flex items-center justify-between">
          <span className="flex items-center gap-1.5 text-xs font-bold">
            <AlarmClock className="size-4" aria-hidden="true" />
            NEXT MISSION
          </span>
          <span className="rounded-full bg-lime-foreground px-2.5 py-1 text-[10px] font-bold text-lime">
            あと 7時間42分
          </span>
        </div>
        <p id="next-alarm" className="mt-2 font-display text-[76px] leading-none tracking-tight">
          07:30
        </p>
        <p className="mt-1 text-sm font-bold">明日・木曜｜1限あるやつ</p>
        <div className="mt-4 flex items-center gap-2">
          <span className="rounded-md border-2 border-lime-foreground px-2 py-0.5 text-[11px] font-bold">
            制限: SNS / ショート動画
          </span>
          <span className="rounded-md border-2 border-lime-foreground px-2 py-0.5 text-[11px] font-bold">
            解除: 離床
          </span>
        </div>
      </section>

      {/* Watcher + bed status */}
      <div className="grid grid-cols-5 gap-3">
        <Card className="relative col-span-3 min-h-48 overflow-hidden p-0">
          <Watcher className="absolute inset-0 top-6 animate-float" priority />
          <div className="relative ml-8 -translate-x-5 w-[170px] h-[90px]">
            {/* 吹き出し全体の形 */}
            <svg
              viewBox="0 0 200 60"
              className="absolute inset-0 h-full w-full overflow-visible"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="
                  M 15 2
                  H 185
                  Q 198 2 198 15
                  V 35
                  Q 198 48 185 48
                  H 55
                  L 55 58
                  L 30 48
                  H 15
                  Q 2 48 2 35
                  V 15
                  Q 2 2 15 2
                  Z
                "
                fill="#ffffff"
                stroke="#1b1b1b"
                strokeWidth="3"
                strokeLinejoin="round"
              />
            </svg>

            {/* セリフ */}
            <p className="absolute left-1 top-[31px] w-full text-center text-[12px] font-bold text-black">
              「布団に負けるなよ。」
            </p>
          </div>
          <Sticker className="absolute bottom-3 left-3 -rotate-3">ドパ監視員</Sticker>
        </Card>
        <div className="col-span-2 flex flex-col gap-3">
          <Card className="flex flex-1 flex-col justify-between">
            <BedDouble className="size-5 text-lime" aria-hidden="true" />
            <div>
              <p className="text-[10px] text-muted-foreground">BED SENSOR</p>
              <p className="flex items-center gap-1.5 whitespace-nowrap font-display text-base">
                <span className="relative flex size-2">
                  <span className="absolute inline-flex size-full animate-ring rounded-full bg-lime" />
                  <span className="relative inline-flex size-2 rounded-full bg-lime" />
                </span>
                IN BED
              </p>
            </div>
          </Card>
          <Card className="flex flex-1 flex-col justify-between">
            <Flame className="size-5 text-alert" aria-hidden="true" />
            <div>
              <p className="text-[10px] text-muted-foreground">連続起床</p>
              <p className="font-display text-lg">
                {streak}
                <span className="ml-0.5 text-xs">日</span>
              </p>
            </div>
          </Card>
        </div>
      </div>

      {/* Demo trigger */}
      <button
        type="button"
        onClick={() => setIsAlarmModalOpen(true)}
        className="group flex items-center justify-between rounded-2xl border-2 border-dashed border-lime/50 px-4 py-3.5 text-left transition hover:border-lime hover:bg-lime/5 active:scale-[0.98]"
      >
        <span>
          <span className="block font-display text-sm">アラームを鳴らす</span>
          <span className="block text-[11px] text-muted-foreground">デモ：起床フローを体験する</span>
        </span>
        <span className="flex size-10 items-center justify-center rounded-full bg-lime text-lime-foreground transition group-hover:rotate-12">
          <Zap className="size-5" aria-hidden="true" />
        </span>
      </button>

      {/* Alarm list */}
      <section aria-labelledby="alarm-list" className="flex flex-col gap-3">
        <SectionLabel
          right={
            <button
                type="button"
                onClick={() => setIsAlarmModalOpen(true)}
                className="flex size-8 items-center justify-center rounded-full bg-surface-2 text-foreground"
                aria-label="アラームを追加"
              >
              <Plus className="size-4" />
            </button>
          }
        >
          <span id="alarm-list">ALARMS</span>
        </SectionLabel>
        <ul className="flex flex-col gap-2">
          {alarms.map((a) => (
            <li key={a.id} className="flex items-center justify-between rounded-2xl border bg-surface px-4 py-3">
              <div className={cn('transition', !a.on && 'opacity-40')}>
                <p className="font-display text-3xl leading-none">{a.time}</p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  {a.label}・{a.days}
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={a.on}
                aria-label={`${a.time} のアラーム`}
                onClick={() =>
                  setAlarms((prev) => prev.map((x) => (x.id === a.id ? { ...x, on: !x.on } : x)))
                }
                className={cn(
                  'relative h-7 w-12 rounded-full transition-colors',
                  a.on ? 'bg-lime' : 'bg-surface-2',
                )}
              >
                <span
                  className={cn(
                    'absolute top-1 size-5 rounded-full transition-all',
                    a.on ? 'left-6 bg-lime-foreground' : 'left-1 bg-muted-foreground',
                  )}
                />
              </button>
            </li>
          ))}
        </ul>
      </section>
      {/* アラーム追加モーダル */}
      {isAlarmModalOpen && (
        <AlarmModal
          onClose={() => setIsAlarmModalOpen(false)}
          onSave={(time: string, days: number[]) => {
            const dayNames = ['日', '月', '火', '水', '木', '金', '土']

            const newAlarm: Alarm = {
              id: crypto.randomUUID(),
              time,
              label: '新しいアラーム',
              days:
                days.length > 0
                  ? days.map((d) => dayNames[d]).join(' ')
                  : '1回のみ',
              on: true,
            }

            setAlarms((prev) => [...prev, newAlarm])
            setIsAlarmModalOpen(false)
          }}
        />
      )}
    </div>
  )
}
