import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HFModelDTO } from '../rest';

type DisplayMode = 'standard' | 'detail' | 'json';

@Component({
  imports: [CommonModule],
  selector: 'app-hfmodel-detail-panel',
  templateUrl: './hfmodel-detail-panel.component.html',
  styleUrls: ['./hfmodel-detail-panel.component.css'],
})
export class HFModelDetailPanelComponent {
  @Input() selectedModel: HFModelDTO | null = null;
  displayMode: DisplayMode = 'standard';

  setDisplayMode(mode: DisplayMode) {
    this.displayMode = mode;
  }

  getJsonDump(): string {
    return JSON.stringify(this.selectedModel, null, 2);
  }
}
