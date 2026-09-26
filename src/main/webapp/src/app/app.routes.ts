import {Routes} from '@angular/router';
import {AboutPage} from './about-page/about-page.component';
import {SourcesSyncPage} from './sources-sync-page/sources-sync.component';

export const routes: Routes = [
  {path:'about', component: AboutPage},
  {path:'sources-sync', component: SourcesSyncPage},

];
