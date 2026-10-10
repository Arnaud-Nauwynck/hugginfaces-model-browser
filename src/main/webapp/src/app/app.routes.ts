import {Routes} from '@angular/router';
import {AboutPage} from './about-page/about-page.component';
import {SourcesSyncPage} from './sources-sync-page/sources-sync.component';
import {HFModelsSearchPage} from './hfmodels-search-page/hfmodels-search-page.component';

export const routes: Routes = [
  {path:'llm-models', component: HFModelsSearchPage},
  {path:'about', component: AboutPage},
  {path:'sources-sync', component: SourcesSyncPage},

];
