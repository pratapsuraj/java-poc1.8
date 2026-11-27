import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, switchMap, takeWhile, timer } from 'rxjs';

/** Statuses of a run that is still working in the background; the UI keeps polling while it has one of these. */
const ACTIVE = ['QUEUED', 'RUNNING'];

interface RawRow { date: string; orderId: string; productId: string; region: string; qty: number; amount: number; }
interface AggRow { date: string; region: string; productId: string; totalQty: number; totalAmount: number; orderCount: number; }

interface BatchStatus {
  processInstanceId: string;
  status: string;
  error?: string;
}

/** Test UI for the Sales Batch API: upload a CSV, watch the status, approve or reject. */
@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  businessDate = this.today();
  file: File | null = null;
  approver = 'alice';

  readonly batch = signal<BatchStatus | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly history = signal<BatchStatus[]>([]);
  readonly rawRows = signal<RawRow[]>([]);
  readonly aggRows = signal<AggRow[]>([]);
  readonly dataLoaded = signal(false);

  constructor(private http: HttpClient) {}

  onFile(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  /** POST /batches/upload. Returns 202 at once; the run continues in the background and is polled below. */
  upload(): void {
    if (!this.file) {
      this.error.set('Choose a CSV file first.');
      return;
    }
    const form = new FormData();
    form.append('file', this.file);
    form.append('businessDate', this.businessDate);
    this.call(this.http.post<BatchStatus>('/batches/upload', form));
  }

  /** GET /batches/{id} */
  refresh(): void {
    const b = this.batch();
    if (b) {
      this.call(this.http.get<BatchStatus>(`/batches/${b.processInstanceId}`));
    }
  }

  /** POST /batches/{id}/approve */
  decide(approved: boolean): void {
    const b = this.batch();
    if (b) {
      this.call(this.http.post<BatchStatus>(`/batches/${b.processInstanceId}/approve`, null,
        { params: { approved, approver: this.approver } }));
    }
  }

  /** GET /data/raw and /data/agg: what is currently stored in HBase for the business date. */
  loadData(): void {
    if (!/^\d{8}$/.test(this.businessDate)) {
      this.error.set('Business date must be yyyyMMdd.');
      return;
    }
    const params = { date: this.businessDate };
    this.http.get<RawRow[]>('/data/raw', { params }).subscribe({
      next: rows => { this.rawRows.set(rows); this.dataLoaded.set(true); },
      error: (e: HttpErrorResponse) => this.error.set(e.error?.error ?? `${e.status} ${e.statusText}`)
    });
    this.http.get<AggRow[]>('/data/agg', { params }).subscribe({
      next: rows => this.aggRows.set(rows),
      error: (e: HttpErrorResponse) => this.error.set(e.error?.error ?? `${e.status} ${e.statusText}`)
    });
  }

  isActive(status: string | undefined): boolean {
    return !!status && ACTIVE.includes(status);
  }

  private poll?: Subscription;

  /** GET /batches/{id} every 2 s while the run is QUEUED or RUNNING; loads the HBase tables when it settles. */
  private startPolling(id: string): void {
    this.poll?.unsubscribe();
    this.poll = timer(2000, 2000).pipe(
      switchMap(() => this.http.get<BatchStatus>(`/batches/${id}`)),
      takeWhile(s => this.isActive(s.status), true)
    ).subscribe({
      next: s => this.show(s),
      complete: () => this.loadData(),
      error: (e: HttpErrorResponse) => this.error.set(e.error?.error ?? `${e.status} ${e.statusText}`)
    });
  }

  /** Shows a status; adds a history line only when the status really changed. */
  private show(result: BatchStatus): void {
    const last = this.batch();
    this.batch.set(result);
    if (!last || last.processInstanceId !== result.processInstanceId || last.status !== result.status) {
      this.history.update(h => [result, ...h].slice(0, 10));
    }
  }

  private call(request: Observable<BatchStatus>): void {
    this.busy.set(true);
    this.error.set('');
    request.subscribe({
      next: result => {
        this.busy.set(false);
        this.show(result);
        if (this.isActive(result.status)) {
          this.startPolling(result.processInstanceId);
        } else {
          this.loadData();
        }
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(e.error?.error ?? `${e.status} ${e.statusText}`);
      }
    });
  }

  private today(): string {
    const d = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`;
  }
}
