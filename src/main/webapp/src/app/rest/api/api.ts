export * from './huggingFaceModels.service';
import { HuggingFaceModelsService } from './huggingFaceModels.service';
export * from './huggingFaceSync.service';
import { HuggingFaceSyncService } from './huggingFaceSync.service';
export * from './probeRestController.service';
import { ProbeRestControllerService } from './probeRestController.service';
export const APIS = [HuggingFaceModelsService, HuggingFaceSyncService, ProbeRestControllerService];
