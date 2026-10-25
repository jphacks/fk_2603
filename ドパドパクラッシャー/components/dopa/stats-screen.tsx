import { Crown, Skull, Trophy } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Card, SectionLabel, Sticker, Watcher } from './primitives'

const week = [
  { day: '月', seconds: 72, win: true },
  { day: '火', seconds: 140, win: true },
  { day: '水', seconds: 610, win: false },
  { day: '木', seconds: 95, win: true },
  { day: '金', seconds: 48, win: true },
  { day: '土', seconds: 220, win: true },
  { day: '日', seconds: 0, win: null },
]

const badges = [
  { name: '布団スレイヤー', desc: '7日連続で3分以内に離床', got: true, rotate: '-rotate-3' },
  { name: '1限の覇者', desc: '1限に10回間に合う', got: true, rotate: 'rotate-2' },
  { name: '秒で起きる人', desc: '30秒以内に離床', got: true, rotate: '-rotate-1' },
  { name: 'ドパ断ち', desc: '朝のSNS 0分を5日', got: false, rotate: 'rotate-3' },
  { name: '二度寝王', desc: '???', got: false, rotate: '-rotate-2' },
  { name: '朝活ガチ勢', desc: '6時台に5回起床', got: false, rotate: 'rotate-1' },
]

export function StatsScreen({ level, streak }: { level: number; streak: number }) {
  const max = Math.max(...week.map((w) => w.seconds))
  const wins = week.filter((w) => w.win === true).length
  const losses = week.filter((w) => w.win === false).length

  return (
    <div className="flex flex-col gap-5 px-5 pb-6">
      <header className="pt-1">
        <p className="text-xs font-medium text-muted-foreground">RECORD</p>
        <h1 className="font-display text-2xl">戦績</h1>
      </header>

      {/* Player card */}
      <section className="relative overflow-hidden rounded-[28px] border bg-surface">
        <div className="flex items-stretch">
          <div className="flex flex-1 flex-col justify-between p-5">
            <div>
              <Sticker className="-rotate-2">称号：布団スレイヤー</Sticker>
              <p className="mt-3 text-[11px] text-muted-foreground">PLAYER</p>
              <p className="font-display text-xl">ねむい大学生</p>
            </div>
            <div className="mt-4 flex gap-5">
              <div>
                <p className="text-[10px] text-muted-foreground">LEVEL</p>
                <p className="font-display text-3xl text-lime">{level}</p>
              </div>
              <div>
                <p className="text-[10px] text-muted-foreground">RANK</p>
                <p className="font-display text-3xl">A+</p>
              </div>
            </div>
          </div>
          <Watcher mood="clear" className="w-36 shrink-0" />
        </div>
        <div className="grid grid-cols-3 border-t">
          {[
            { label: '今週', value: `${wins}W ${losses}L` },
            { label: '連続', value: `${streak}日` },
            { label: '最速', value: '0:48' },
          ].map((s, i) => (
            <div key={s.label} className={cn('px-4 py-3', i > 0 && 'border-l')}>
              <p className="text-[10px] text-muted-foreground">{s.label}</p>
              <p className="font-display text-base">{s.value}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Weekly chart */}
      <section aria-labelledby="weekly" className="flex flex-col gap-3">
        <SectionLabel right={<span className="text-[11px] text-muted-foreground">離床までの時間</span>}>
          <span id="weekly">THIS WEEK</span>
        </SectionLabel>
        <Card>
          <ul className="flex h-40 items-end justify-between gap-2">
            {week.map((w) => (
              <li key={w.day} className="flex flex-1 flex-col items-center gap-2">
                <span className="text-[10px] tabular-nums text-muted-foreground">
                  {w.seconds ? `${Math.floor(w.seconds / 60)}:${String(w.seconds % 60).padStart(2, '0')}` : '-'}
                </span>
                <span
                  className={cn(
                    'w-full rounded-[6px]',
                    w.win === true && 'bg-lime',
                    w.win === false && 'bg-alert',
                    w.win === null && 'border border-dashed border-muted-foreground/40',
                  )}
                  style={{ height: w.seconds ? `${Math.max(10, (w.seconds / max) * 96)}px` : '10px' }}
                />
                <span className="flex size-6 items-center justify-center rounded-full bg-surface-2 text-[10px] font-bold">
                  {w.day}
                </span>
                <span className="sr-only">
                  {w.win === null ? '未実施' : w.win ? '勝ち' : '負け（二度寝）'}
                </span>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex items-center gap-4 border-t pt-3 text-[11px] text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <Trophy className="size-3.5 text-lime" aria-hidden="true" /> WIN：5分以内に離床
            </span>
            <span className="flex items-center gap-1.5">
              <Skull className="size-3.5 text-alert" aria-hidden="true" /> LOSE：二度寝
            </span>
          </div>
        </Card>
      </section>

      {/* Ranking */}
      <section aria-labelledby="ranking" className="flex flex-col gap-3">
        <SectionLabel>
          <span id="ranking">ゼミ内ランキング</span>
        </SectionLabel>
        <ol className="flex flex-col gap-2">
          {[
            { name: 'たかし', time: '0:41', me: false },
            { name: 'あなた', time: '0:48', me: true },
            { name: 'ゆうな', time: '1:22', me: false },
            { name: 'けんと', time: '9:58', me: false },
          ].map((p, i) => (
            <li
              key={p.name}
              className={cn(
                'flex items-center gap-3 rounded-2xl border px-4 py-3',
                p.me ? 'border-lime bg-lime/10' : 'bg-surface',
              )}
            >
              <span className="w-5 font-display text-lg text-muted-foreground">{i + 1}</span>
              {i === 0 && <Crown className="size-4 text-lime" aria-label="1位" />}
              <span className="flex-1 text-sm font-bold">{p.name}</span>
              <span className="font-display tabular-nums">{p.time}</span>
            </li>
          ))}
        </ol>
      </section>

      {/* Badges */}
      <section aria-labelledby="badges" className="flex flex-col gap-3">
        <SectionLabel right={<span className="text-[11px] text-muted-foreground">3 / 6</span>}>
          <span id="badges">STICKERS</span>
        </SectionLabel>
        <ul className="grid grid-cols-3 gap-2">
          {badges.map((b) => (
            <li
              key={b.name}
              className={cn(
                'flex aspect-square flex-col items-center justify-center gap-1 rounded-2xl border p-2 text-center',
                b.got ? 'bg-surface' : 'border-dashed bg-transparent',
              )}
            >
              <span
                className={cn(
                  'rounded-lg border-2 px-2 py-1 font-display text-[11px] leading-tight',
                  b.rotate,
                  b.got
                    ? 'border-background bg-lime text-lime-foreground shadow-[3px_3px_0_0_rgb(255_255_255_/_0.15)]'
                    : 'border-muted-foreground/30 text-muted-foreground/50',
                )}
              >
                {b.got ? b.name : '？？？'}
              </span>
              <span className="text-[9px] leading-tight text-muted-foreground">{b.desc}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
