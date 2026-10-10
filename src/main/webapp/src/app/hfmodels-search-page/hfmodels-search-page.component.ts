import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AgGridModule } from 'ag-grid-angular';
import { ColDef, SelectionChangedEvent } from 'ag-grid-community';
import { HFModelDTO, HFModelQueryDTO, HFModelCriteriaDTO, HuggingFaceModelsService, ModelStatisticsDTO } from '../rest';
import { HFModelDetailPanelComponent } from './hfmodel-detail-panel.component';
import { ModelStatisticsComponent } from './model-statistics.component';

@Component({
  imports: [CommonModule, FormsModule, AgGridModule, HFModelDetailPanelComponent, ModelStatisticsComponent],
  selector: 'app-hfmodels-search',
  templateUrl: './hfmodels-search-page.component.html',
  styleUrls: ['./hfmodels-search-page.component.css'],
})
export class HFModelsSearchPage implements OnInit {
  models = signal<HFModelDTO[]>([]);
  selectedModel = signal<HFModelDTO | null>(null);
  statistics = signal<ModelStatisticsDTO | null>(null);
  searchLoading = signal(false);
  statisticsLoading = signal(false);
  showCriteria = signal(false);

  authorContains = signal('');
  authorEquals = signal('');
  limit = signal(100);
  createdDateRange = signal<string>('');
  createdDateFrom = signal<string>('');
  createdDateTo = signal<string>('');

  gridColDefs = signal<ColDef[]>([
    { field: 'huggingFaceInfo.modelId', headerName: 'Model ID', flex: 1, minWidth: 200 },
    { field: 'huggingFaceInfo.author', headerName: 'Author', flex: 0.8 },
    { field: 'huggingFaceInfo.downloads', headerName: 'Downloads', flex: 0.6, type: 'numericColumn' },
    { field: 'huggingFaceInfo.likes', headerName: 'Likes', flex: 0.6, type: 'numericColumn' },
    { field: 'huggingFaceInfo.pipeline_tag', headerName: 'Pipeline', flex: 0.8 },
  ]);

  constructor(
    private huggingFaceModelsService: HuggingFaceModelsService
  ) {}

  ngOnInit() {
  }

  search() {
    this.searchLoading.set(true);
    this.selectedModel.set(null);

    const criteria: HFModelCriteriaDTO = {};
    if (this.authorContains().trim()) {
      criteria.authorContains = this.authorContains();
    }
    if (this.authorEquals().trim()) {
      criteria.authorEquals = this.authorEquals();
    }

    const { dateFrom, dateTo } = this.getDateRange();
    if (dateFrom) {
      criteria.createdDateFrom = dateFrom;
    }
    if (dateTo) {
      criteria.createdDateTo = dateTo;
    }

    const query: HFModelQueryDTO = {
      criteria,
      limit: this.limit(),
    };

    this.huggingFaceModelsService.queryModels(query).subscribe({
      next: (results) => {
        this.models.set(results);
        this.searchLoading.set(false);
      },
      error: (ex) => {
        console.error("Failed to search models", ex);
        this.searchLoading.set(false);
        this.models.set([]);
      }
    });
  }

  computeStatistics() {
    this.statisticsLoading.set(true);

    const criteria: HFModelCriteriaDTO = {};
    if (this.authorContains().trim()) {
      criteria.authorContains = this.authorContains();
    }
    if (this.authorEquals().trim()) {
      criteria.authorEquals = this.authorEquals();
    }

    const { dateFrom, dateTo } = this.getDateRange();
    if (dateFrom) {
      criteria.createdDateFrom = dateFrom;
    }
    if (dateTo) {
      criteria.createdDateTo = dateTo;
    }

    const query: HFModelQueryDTO = {
      criteria,
      limit: this.limit(),
    };

    this.huggingFaceModelsService.computeStatistics(query).subscribe({
      next: (stats) => {
        this.statistics.set(stats);
        this.statisticsLoading.set(false);
      },
      error: (ex) => {
        console.error("Failed to compute statistics", ex);
        this.statisticsLoading.set(false);
        this.statistics.set(null);
      }
    });
  }

  private getDateRange(): { dateFrom: string | null, dateTo: string | null } {
    const range = this.createdDateRange();

    if (range === 'advanced') {
      return {
        dateFrom: this.createdDateFrom().trim() || null,
        dateTo: this.createdDateTo().trim() || null,
      };
    }

    const now = new Date();
    const today = HFModelsSearchPage.formatDate(now);

    function lastNYears(n: number): { dateFrom: string | null; dateTo: string | null } {
      const lastNy = new Date(now.getTime() - n * 365 * 24 * 60 * 60 * 1000);
      return { dateFrom: HFModelsSearchPage.formatDate(lastNy), dateTo: today };
    }
    function lastNMonths(n: number): { dateFrom: string | null; dateTo: string | null } {
      const lastNy = new Date(now.getTime() - n * 30 * 24 * 60 * 60 * 1000);
      return { dateFrom: HFModelsSearchPage.formatDate(lastNy), dateTo: today };
    }

    switch (range) {
      case 'last1w':
        const last1w = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
        return { dateFrom: HFModelsSearchPage.formatDate(last1w), dateTo: today };
      case 'last1m':
        return lastNMonths(1);
      case 'last3m':
        return lastNMonths(3);
      case 'last6m':
        return lastNMonths(6);
      case 'last1y':
        return lastNYears(1);
      case 'last2y':
        return lastNYears(2);
      case 'last3y':
        return lastNYears(3);
      case 'last4y':
        return lastNYears(4);
      case 'last5y':
        return lastNYears(5);
      default:
        return { dateFrom: null, dateTo: null };
    }
  }

  private static formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  onSelectionChanged(event: SelectionChangedEvent<HFModelDTO>) {
    const selected = event.api.getSelectedRows();
    this.selectedModel.set(selected.length > 0 ? selected[0] : null);
  }

  clearSearch() {
    this.authorContains.set('');
    this.authorEquals.set('');
    this.limit.set(100);
    this.createdDateRange.set('');
    this.createdDateFrom.set('');
    this.createdDateTo.set('');
    this.models.set([]);
    this.selectedModel.set(null);
    this.statistics.set(null);
  }

  onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      this.search();
    }
  }

  onCreatedDateRangeChange(event: Event) {
    const target = event.target as HTMLSelectElement;
    this.createdDateRange.set(target.value);
  }
}
