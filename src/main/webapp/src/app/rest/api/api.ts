export * from './huggingFaceSync.service';
import { HuggingFaceSyncService } from './huggingFaceSync.service';
export * from './probeRestController.service';
import { ProbeRestControllerService } from './probeRestController.service';
export const APIS = [HuggingFaceSyncService, ProbeRestControllerService];
