import { Component, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { NgbCollapseModule, NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';

@Component({
  imports: [RouterLink, RouterOutlet, NgbCollapseModule, NgbDropdownModule],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  protected readonly title = signal('webapp');
  protected isNavbarCollapsed = true;
}
