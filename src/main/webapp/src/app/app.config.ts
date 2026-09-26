import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  importProvidersFrom,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { routes } from './app.routes';
import { NgbModule } from '@ng-bootstrap/ng-bootstrap';
import { provideApi } from './rest/provide-api';
import { provideHighcharts } from 'highcharts-angular';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(),
    importProvidersFrom(NgbModule),
    provideApi(''),
    provideHighcharts(),
  ],
};
