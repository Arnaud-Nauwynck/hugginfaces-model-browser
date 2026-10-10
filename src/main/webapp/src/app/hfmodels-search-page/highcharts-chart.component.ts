import { Component, Input, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';

declare var Highcharts: any;

@Component({
  imports: [CommonModule],
  selector: 'app-highcharts-chart',
  templateUrl: './highcharts-chart.component.html',
  styleUrls: ['./highcharts-chart.component.css'],
})
export class HighchartsChartComponent implements OnInit {
  @Input() options: any;
  @ViewChild('container') container!: ElementRef;

  ngOnInit() {
    this.loadHighcharts();
  }

  private loadHighcharts() {
    if (typeof Highcharts === 'undefined') {
      const script = document.createElement('script');
      script.src = 'https://code.highcharts.com/highcharts.js';
      script.onload = () => {
        this.renderChart();
      };
      document.head.appendChild(script);
    } else {
      this.renderChart();
    }
  }

  private renderChart() {
    if (this.container && this.options) {
      Highcharts.chart(this.container.nativeElement, this.options);
    }
  }

  ngAfterViewInit() {
    if (typeof Highcharts !== 'undefined') {
      this.renderChart();
    }
  }
}
