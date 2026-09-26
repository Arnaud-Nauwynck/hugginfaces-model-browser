import { Component, DestroyRef, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';
import {HuggingFaceSynchronizationStateDTO, HuggingFaceSyncService} from '../rest';


@Component({
  imports: [],
  selector: 'app-sources-sync',
  templateUrl: './sources-sync.component.html',
})
export class SourcesSyncPage implements OnInit {
  showAdvanced = signal(false);

  running = signal(false);
  huggingFaceSyncRunning = signal(false);

  lastSyncState = signal<HuggingFaceSynchronizationStateDTO>({});

  constructor(
    private huggingFaceSyncService: HuggingFaceSyncService
  ) {}

  ngOnInit() {
    this.refreshLastSync();
  }

  formatInstant(isoDateTime: string | undefined): string | undefined {
    return isoDateTime != null ? new Date(isoDateTime).toLocaleString() : undefined;
  }

  refreshLastSync() {
    this.huggingFaceSyncService.getLastSyncState().subscribe({
      next: (status) => this.lastSyncState.set(status),
      error: (ex) => console.error("... Failed to load last sync info for jira-sync", ex),
    });
  }


  runSyncAll() {
    this.running.set(true);
    console.log("call http POST /api/v1/huggingface-sync/run-sync-all");
    this.huggingFaceSyncService.runSyncAll().subscribe({
      next: () => {
        this.running.set(false);
        console.log("... done call http POST /api/v1/huggingface-sync/run-sync-all");
        this.refreshLastSync();
      },
      error: (ex) => {
        this.running.set(false);
        console.error("... Failed call http POST /api/v1/huggingface-sync/run-sync-all", ex);
      }
    });
  }


  runSyncIncremental() {
    this.running.set(true);
    console.log("call http POST /api/v1/huggingface-sync/run-sync-incremental");
    this.huggingFaceSyncService.runSyncIncremental().subscribe({
      next: () => {
        this.running.set(false);
        console.log("... done call http POST /api/v1/huggingface-sync/run-sync-incremental");
        this.refreshLastSync();
      },
      error: (ex) => {
        this.running.set(false);
        console.error("... Failed call http POST /api/v1/huggingface-sync/run-sync-incremental", ex);
      }
    });
  }

}
