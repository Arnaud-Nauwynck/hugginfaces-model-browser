import { Component, Input, OnChanges, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AgGridModule } from 'ag-grid-angular';
import { ColDef } from 'ag-grid-community';
import { ModelStatisticsDTO } from '../rest';
import { HighchartsChartComponent } from './highcharts-chart.component';

interface StatisticRow {
  month: string;
  author: string;
  sumLikes: number;
  sumDownloads: number;
}

interface ModelStatisticRow {
  month: string;
  model: string;
  likes: number;
  downloads: number;
  author: string;
}

type DisplayMode = 'grid' | 'charts' | 'modelCharts';

@Component({
  imports: [CommonModule, AgGridModule, HighchartsChartComponent],
  selector: 'app-model-statistics',
  templateUrl: './model-statistics.component.html',
  styleUrls: ['./model-statistics.component.css'],
})
export class ModelStatisticsComponent implements OnChanges {
  @Input() statistics: ModelStatisticsDTO | null = null;

  displayMode = signal<DisplayMode>('grid');
  rowData = signal<StatisticRow[]>([]);
  modelRowData = signal<ModelStatisticRow[]>([]);
  likesChartOptions = signal<any>(null);
  downloadsChartOptions = signal<any>(null);
  modelLikesChartOptions = signal<any>(null);
  modelDownloadsChartOptions = signal<any>(null);

  gridColDefs = signal<ColDef[]>([
    {
      field: 'month',
      headerName: 'Month',
      flex: 0.6,
      minWidth: 100,
    },
    {
      field: 'author',
      headerName: 'Author',
      flex: 1,
      minWidth: 150,
    },
    {
      field: 'sumLikes',
      headerName: 'Total Likes',
      flex: 0.6,
      minWidth: 100,
      type: 'numericColumn',
      aggFunc: 'sum',
    },
    {
      field: 'sumDownloads',
      headerName: 'Total Downloads',
      flex: 0.6,
      minWidth: 120,
      type: 'numericColumn',
      aggFunc: 'sum',
    },
  ]);

  modelGridColDefs = signal<ColDef[]>([
    {
      field: 'month',
      headerName: 'Month',
      flex: 0.6,
      minWidth: 100,
    },
    {
      field: 'model',
      headerName: 'Model',
      flex: 1.5,
      minWidth: 200,
    },
    {
      field: 'author',
      headerName: 'Author',
      flex: 1,
      minWidth: 150,
    },
    {
      field: 'likes',
      headerName: 'Likes',
      flex: 0.6,
      minWidth: 100,
      type: 'numericColumn',
      aggFunc: 'sum',
    },
    {
      field: 'downloads',
      headerName: 'Downloads',
      flex: 0.6,
      minWidth: 120,
      type: 'numericColumn',
      aggFunc: 'sum',
    },
  ]);

  ngOnChanges(changes: SimpleChanges) {
    if (changes['statistics']) {
      this.buildRowData();
      this.buildChartData();
    }
  }

  setDisplayMode(mode: DisplayMode) {
    this.displayMode.set(mode);
  }

  private buildRowData() {
    const rows: StatisticRow[] = [];

    if (this.statistics?.byMonthThenAuthor) {
      const months = Object.keys(this.statistics.byMonthThenAuthor).sort();

      for (const month of months) {
        const authorMap = this.statistics.byMonthThenAuthor[month];
        const authors = Object.keys(authorMap).sort();

        for (const author of authors) {
          const stats = authorMap[author];
          rows.push({
            month,
            author,
            sumLikes: stats.sumLikes ?? 0,
            sumDownloads: stats.sumDownloads ?? 0,
          });
        }
      }
    }

    this.rowData.set(rows);

    const modelRows: ModelStatisticRow[] = [];

    if (this.statistics?.byMonthThenModel) {
      const months = Object.keys(this.statistics.byMonthThenModel).sort();

      for (const month of months) {
        const modelMap = this.statistics.byMonthThenModel[month];
        const models = Object.keys(modelMap).sort();

        for (const model of models) {
          const stats = modelMap[model];
          modelRows.push({
            month,
            model,
            likes: stats.likes ?? 0,
            downloads: stats.downloads ?? 0,
            author: stats.author ?? 'unknown',
          });
        }
      }
    }

    this.modelRowData.set(modelRows);
  }

  private buildChartData() {
    this.buildAuthorCharts();
    this.buildModelCharts();
  }

  private buildAuthorCharts() {
    if (!this.statistics?.byMonthThenAuthor) {
      this.likesChartOptions.set(null);
      this.downloadsChartOptions.set(null);
      return;
    }

    const months = Object.keys(this.statistics.byMonthThenAuthor).sort();

    const authorLikesTotals = new Map<string, number>();
    const authorDownloadsTotals = new Map<string, number>();
    const monthsByAuthorLikes = new Map<string, Map<string, number>>();
    const monthsByAuthorDownloads = new Map<string, Map<string, number>>();

    for (const month of months) {
      const authorMap = this.statistics.byMonthThenAuthor[month];
      for (const author in authorMap) {
        const stats = authorMap[author];
        const likes = stats.sumLikes ?? 0;
        const downloads = stats.sumDownloads ?? 0;

        authorLikesTotals.set(author, (authorLikesTotals.get(author) ?? 0) + likes);
        authorDownloadsTotals.set(author, (authorDownloadsTotals.get(author) ?? 0) + downloads);

        if (!monthsByAuthorLikes.has(author)) {
          monthsByAuthorLikes.set(author, new Map());
        }
        monthsByAuthorLikes.get(author)!.set(month, likes);

        if (!monthsByAuthorDownloads.has(author)) {
          monthsByAuthorDownloads.set(author, new Map());
        }
        monthsByAuthorDownloads.get(author)!.set(month, downloads);
      }
    }

    const top20AuthorsByLikes = Array.from(authorLikesTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 20)
      .map(([author]) => author);

    const top20AuthorsByDownloads = Array.from(authorDownloadsTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 20)
      .map(([author]) => author);

    const likesChartOptions = this.buildChartOptions(
      months,
      top20AuthorsByLikes,
      monthsByAuthorLikes,
      'Top 20 Authors by Likes Over Time',
      'Total Likes'
    );

    const downloadsChartOptions = this.buildChartOptions(
      months,
      top20AuthorsByDownloads,
      monthsByAuthorDownloads,
      'Top 20 Authors by Downloads Over Time',
      'Total Downloads'
    );

    this.likesChartOptions.set(likesChartOptions);
    this.downloadsChartOptions.set(downloadsChartOptions);
  }

  private buildModelCharts() {
    if (!this.statistics?.byMonthThenModel) {
      this.modelLikesChartOptions.set(null);
      this.modelDownloadsChartOptions.set(null);
      return;
    }

    const months = Object.keys(this.statistics.byMonthThenModel).sort();

    const modelLikesTotals = new Map<string, number>();
    const modelDownloadsTotals = new Map<string, number>();
    const monthsByModelLikes = new Map<string, Map<string, number>>();
    const monthsByModelDownloads = new Map<string, Map<string, number>>();

    for (const month of months) {
      const modelMap = this.statistics.byMonthThenModel[month];
      for (const model in modelMap) {
        const stats = modelMap[model];
        const likes = stats.likes ?? 0;
        const downloads = stats.downloads ?? 0;

        modelLikesTotals.set(model, (modelLikesTotals.get(model) ?? 0) + likes);
        modelDownloadsTotals.set(model, (modelDownloadsTotals.get(model) ?? 0) + downloads);

        if (!monthsByModelLikes.has(model)) {
          monthsByModelLikes.set(model, new Map());
        }
        monthsByModelLikes.get(model)!.set(month, likes);

        if (!monthsByModelDownloads.has(model)) {
          monthsByModelDownloads.set(model, new Map());
        }
        monthsByModelDownloads.get(model)!.set(month, downloads);
      }
    }

    const top20ModelsByLikes = Array.from(modelLikesTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 20)
      .map(([model]) => model);

    const top20ModelsByDownloads = Array.from(modelDownloadsTotals.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 20)
      .map(([model]) => model);

    const modelLikesChartOptions = this.buildChartOptions(
      months,
      top20ModelsByLikes,
      monthsByModelLikes,
      'Top 20 Models by Likes Over Time',
      'Likes'
    );

    const modelDownloadsChartOptions = this.buildChartOptions(
      months,
      top20ModelsByDownloads,
      monthsByModelDownloads,
      'Top 20 Models by Downloads Over Time',
      'Downloads'
    );

    this.modelLikesChartOptions.set(modelLikesChartOptions);
    this.modelDownloadsChartOptions.set(modelDownloadsChartOptions);
  }

  private buildChartOptions(
    months: string[],
    topAuthors: string[],
    monthsByAuthor: Map<string, Map<string, number>>,
    title: string,
    yAxisLabel: string
  ): any {
    const series = topAuthors.map(author => ({
      name: author,
      data: months.map((month, index) => ({
        x: index,
        y: monthsByAuthor.get(author)?.get(month) ?? 0,
      })),
    }));

    return {
      chart: {
        type: 'line',
        height: 400,
      },
      title: {
        text: title,
      },
      xAxis: {
        categories: months,
        title: {
          text: 'Month',
        },
        labels: {
          format: '{value}',
        },
        type: 'category',
      },
      yAxis: {
        title: {
          text: yAxisLabel,
        },
      },
      tooltip: {
        headerFormat: '<b>{point.category}</b><br/>',
        pointFormat: '<b>{series.name}</b>: {point.y:,.0f}<br/>',
      },
      plotOptions: {
        line: {
          dataLabels: {
            enabled: false,
          },
          enableMouseTracking: true,
        },
      },
      series,
    };
  }
}
