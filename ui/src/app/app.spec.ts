import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { App } from './app';

const RAW = [{ date: '20261115', orderId: 'B1', productId: 'P1', region: 'EU', qty: 2, amount: 10.5 }];
const AGG = [{ date: '20261115', region: 'EU', productId: 'P1', totalQty: 2, totalAmount: 10.5, orderCount: 1 }];

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let app: App;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    fixture = TestBed.createComponent(App);
    app = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  /** Answers the two "Data in HBase" requests that follow every settled run. */
  function answerDataRequests(raw = RAW, agg = AGG): void {
    http.expectOne(r => r.url === '/data/raw').flush(raw);
    http.expectOne(r => r.url === '/data/agg').flush(agg);
  }

  function chooseFile(): void {
    app.file = new File(['orderId,productId,region,qty,amount\nB1,P1,EU,2,10.50\n'], 'sales.csv', { type: 'text/csv' });
  }

  function buttonLabelled(text: string): HTMLButtonElement {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const found = buttons.find(b => b.textContent?.trim().startsWith(text));
    if (!found) {
      throw new Error('no button labelled ' + text);
    }
    return found;
  }

  // ---- basics -----------------------------------------------------------------------------------

  it('starts with today as the business date (yyyyMMdd) and the default approver', () => {
    expect(app.businessDate).toMatch(/^\d{8}$/);
    expect(app.approver).toBe('alice');
    expect(app.batch()).toBeNull();
  });

  it('knows which statuses are still being worked on', () => {
    expect(app.isActive('QUEUED')).toBeTrue();
    expect(app.isActive('RUNNING')).toBeTrue();
    expect(app.isActive('WAITING_APPROVAL')).toBeFalse();
    expect(app.isActive('FAILED')).toBeFalse();
    expect(app.isActive(undefined)).toBeFalse();
  });

  it('remembers the chosen file, or none', () => {
    const file = new File(['x'], 'a.csv');
    app.onFile({ target: { files: [file] } } as unknown as Event);
    expect(app.file).toBe(file);

    app.onFile({ target: { files: [] } } as unknown as Event);
    expect(app.file).toBeNull();
  });

  // ---- upload -----------------------------------------------------------------------------------

  it('asks for a file before uploading', () => {
    app.upload();

    expect(app.error()).toBe('Choose a CSV file first.');
    http.expectNone('/batches/upload');
  });

  it('uploads the file and date as multipart form data', fakeAsync(() => {
    chooseFile();
    app.businessDate = '20261115';

    app.upload();
    expect(app.busy()).toBeTrue();

    const req = http.expectOne('/batches/upload');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body.get('businessDate')).toBe('20261115');
    expect((body.get('file') as File).name).toBe('sales.csv');
    req.flush({ processInstanceId: '7', status: 'WAITING_APPROVAL' });

    expect(app.busy()).toBeFalse();
    answerDataRequests();
  }));

  it('polls every 2 seconds while the run works, then loads the HBase tables', fakeAsync(() => {
    chooseFile();
    app.upload();
    http.expectOne('/batches/upload').flush({ processInstanceId: '7', status: 'QUEUED' });
    expect(app.batch()?.status).toBe('QUEUED');
    expect(app.busy()).toBeFalse();
    http.expectNone('/batches/7'); // nothing before the first 2 seconds

    tick(2000);
    http.expectOne('/batches/7').flush({ processInstanceId: '7', status: 'RUNNING' });
    expect(app.batch()?.status).toBe('RUNNING');

    tick(2000);
    http.expectOne('/batches/7').flush({ processInstanceId: '7', status: 'WAITING_APPROVAL' });
    expect(app.batch()?.status).toBe('WAITING_APPROVAL');

    answerDataRequests(); // the run settled, so polling stopped and the tables are loaded
    expect(app.rawRows().length).toBe(1);
    expect(app.aggRows().length).toBe(1);
    expect(app.dataLoaded()).toBeTrue();

    tick(10000);
    http.expectNone('/batches/7'); // no more polling
    discardPeriodicTasks();
  }));

  it('shows the reason when a polled run fails', fakeAsync(() => {
    chooseFile();
    app.upload();
    http.expectOne('/batches/upload').flush({ processInstanceId: '9', status: 'QUEUED' });

    tick(2000);
    http.expectOne('/batches/9').flush({ processInstanceId: '9', status: 'FAILED', error: 'Line 3: bad number' });

    expect(app.batch()?.status).toBe('FAILED');
    expect(app.batch()?.error).toBe('Line 3: bad number');
    answerDataRequests([], []);
  }));

  it('reports an error from the server', () => {
    chooseFile();
    app.upload();

    http.expectOne('/batches/upload').flush({ error: 'file is empty' }, { status: 400, statusText: 'Bad Request' });

    expect(app.busy()).toBeFalse();
    expect(app.error()).toBe('file is empty');
  });

  it('reports a failure that has no message from the server', () => {
    chooseFile();
    app.upload();

    http.expectOne('/batches/upload').flush(null, { status: 500, statusText: 'Server Error' });

    expect(app.error()).toBe('500 Server Error');
  });

  it('reports an error while polling', fakeAsync(() => {
    chooseFile();
    app.upload();
    http.expectOne('/batches/upload').flush({ processInstanceId: '7', status: 'QUEUED' });

    tick(2000);
    http.expectOne('/batches/7').flush({ error: 'gone' }, { status: 404, statusText: 'Not Found' });

    expect(app.error()).toBe('gone');
  }));

  // ---- refresh, approval, history ---------------------------------------------------------------------

  it('does nothing on refresh or approve before there is a run', () => {
    app.refresh();
    app.decide(true);

    http.expectNone(() => true);
  });

  it('refreshes the status of the current run', () => {
    app.batch.set({ processInstanceId: '7', status: 'WAITING_APPROVAL' });

    app.refresh();

    http.expectOne('/batches/7').flush({ processInstanceId: '7', status: 'WAITING_APPROVAL' });
    answerDataRequests();
  });

  it('approves with the approver name, and rejects', () => {
    app.batch.set({ processInstanceId: '7', status: 'WAITING_APPROVAL' });
    app.approver = 'bob';

    app.decide(true);
    const approve = http.expectOne(r => r.url === '/batches/7/approve');
    expect(approve.request.method).toBe('POST');
    expect(approve.request.params.get('approved')).toBe('true');
    expect(approve.request.params.get('approver')).toBe('bob');
    approve.flush({ processInstanceId: '7', status: 'COMPLETED' });
    answerDataRequests();
    expect(app.batch()?.status).toBe('COMPLETED');

    app.decide(false);
    const reject = http.expectOne(r => r.url === '/batches/7/approve');
    expect(reject.request.params.get('approved')).toBe('false');
    reject.flush({ error: 'No pending approval for process instance 7' }, { status: 409, statusText: 'Conflict' });
    expect(app.error()).toBe('No pending approval for process instance 7');
  });

  it('adds a history line only when the status changes', () => {
    app.batch.set({ processInstanceId: '7', status: 'WAITING_APPROVAL' });
    app.history.set([{ processInstanceId: '7', status: 'WAITING_APPROVAL' }]);

    app.refresh(); // same status again
    http.expectOne('/batches/7').flush({ processInstanceId: '7', status: 'WAITING_APPROVAL' });
    answerDataRequests();
    expect(app.history().length).toBe(1);

    app.decide(true); // a new status
    http.expectOne(r => r.url === '/batches/7/approve').flush({ processInstanceId: '7', status: 'COMPLETED' });
    answerDataRequests();
    expect(app.history().length).toBe(2);
    expect(app.history()[0].status).toBe('COMPLETED');
  });

  it('keeps at most ten history lines', () => {
    for (let i = 0; i < 12; i++) {
      app.batch.set(null);
      app.refresh(); // no run: nothing happens
    }
    for (let i = 0; i < 12; i++) {
      app.batch.set({ processInstanceId: String(i), status: 'WAITING_APPROVAL' });
      app.refresh();
      http.expectOne('/batches/' + i).flush({ processInstanceId: String(i), status: 'COMPLETED' });
      answerDataRequests();
    }

    expect(app.history().length).toBe(10);
  });

  // ---- HBase data -------------------------------------------------------------------------------------

  it('refuses a business date that is not yyyyMMdd', () => {
    app.businessDate = '2026-11-15';

    app.loadData();

    expect(app.error()).toBe('Business date must be yyyyMMdd.');
    http.expectNone(() => true);
  });

  it('loads the rows and the aggregates of the business date', () => {
    app.businessDate = '20261115';

    app.loadData();

    const raw = http.expectOne(r => r.url === '/data/raw');
    const agg = http.expectOne(r => r.url === '/data/agg');
    expect(raw.request.params.get('date')).toBe('20261115');
    expect(agg.request.params.get('date')).toBe('20261115');
    raw.flush(RAW);
    agg.flush(AGG);
    expect(app.rawRows()).toEqual(RAW);
    expect(app.aggRows()).toEqual(AGG);
  });

  it('reports errors of the data endpoints', () => {
    app.loadData();

    http.expectOne(r => r.url === '/data/raw').flush({ error: 'raw failed' }, { status: 500, statusText: 'x' });
    http.expectOne(r => r.url === '/data/agg').flush(null, { status: 503, statusText: 'Unavailable' });

    expect(app.error()).toBe('503 Unavailable'); // the later error wins
  });

  // ---- what the page shows ----------------------------------------------------------------------------------

  it('shows the waiting message while a request is sent', () => {
    app.busy.set(true);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Sending...');
  });

  it('shows errors and the failure reason of a run', () => {
    app.error.set('something broke');
    app.batch.set({ processInstanceId: '3', status: 'FAILED', error: 'Line 3: bad number' });
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('something broke');
    expect(text).toContain('Run failed: Line 3: bad number');
    expect(text).toContain('FAILED');
  });

  it('explains that an active run works in the background', () => {
    app.batch.set({ processInstanceId: '3', status: 'RUNNING' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Working in the background');
  });

  it('enables Approve and Reject only while the run waits for approval', () => {
    app.batch.set({ processInstanceId: '3', status: 'RUNNING' });
    fixture.detectChanges();
    expect(buttonLabelled('Approve').disabled).toBeTrue();
    expect(buttonLabelled('Reject').disabled).toBeTrue();

    app.batch.set({ processInstanceId: '3', status: 'WAITING_APPROVAL' });
    fixture.detectChanges();
    expect(buttonLabelled('Approve').disabled).toBeFalse();
    expect(buttonLabelled('Reject').disabled).toBeFalse();
  });

  it('shows the HBase tables and the history', () => {
    app.rawRows.set(RAW);
    app.aggRows.set(AGG);
    app.dataLoaded.set(true);
    app.history.set([{ processInstanceId: '3', status: 'COMPLETED' }]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Uploaded rows (sales_raw): 1');
    expect(text).toContain('B1');
    expect(text).toContain('10.50');
    expect(text).toContain('Aggregated by Spark (sales_agg): 1');
    expect(text).toContain('Run 3');
  });

  it('says so when there are no rows or aggregates', () => {
    app.dataLoaded.set(true);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('No rows for this date.');
    expect(text).toContain('No aggregates yet');
  });

  it('uploads when the buttons and the file input are used', () => {
    const input = fixture.nativeElement.querySelector('input[type=file]') as HTMLInputElement;
    const transfer = new DataTransfer();
    transfer.items.add(new File(['x'], 'picked.csv'));
    input.files = transfer.files;
    input.dispatchEvent(new Event('change'));
    expect(app.file?.name).toBe('picked.csv');

    buttonLabelled('Upload').click();

    http.expectOne('/batches/upload').flush({ error: 'stop here' }, { status: 400, statusText: 'Bad Request' });
    expect(app.error()).toBe('stop here');
  });
});
