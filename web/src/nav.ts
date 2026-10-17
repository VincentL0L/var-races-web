// Every menu shares the same running chase scene; only its HTML and shading change.
import { app, type Stage } from './app';
import { TitleStage } from './game/backdrops';

/** Reuse the menu race without resetting the camera or cars. */
async function titleStage(): Promise<Stage> {
  const current = app.currentStage();
  return current instanceof TitleStage ? current : TitleStage.create();
}

export const nav = {
  async menu(): Promise<void> {
    const [{ MenuScreen }, stage] = await Promise.all([import('./ui/menu'), titleStage()]);
    app.setStage(stage);
    app.show(new MenuScreen());
  },
  async garage(): Promise<void> {
    const [{ GarageScreen }, stage] = await Promise.all([import('./ui/garage'), titleStage()]);
    app.setStage(stage);
    app.show(new GarageScreen());
  },
  async mapSelect(hosting: false | 'public' | 'private' = false): Promise<void> {
    const [{ MapSelectScreen }, stage] = await Promise.all([import('./ui/mapSelect'), titleStage()]);
    app.setStage(stage);
    app.show(new MapSelectScreen(hosting));
  },
  async multiplayer(): Promise<void> {
    const [{ MultiplayerScreen }, stage] = await Promise.all([import('./ui/multiplayer'), titleStage()]);
    app.setStage(stage);
    app.show(new MultiplayerScreen());
  },
  /** single player: the lobby for a race on this map without opponents */
  async localLobby(mapId: string): Promise<void> {
    const [{ LocalLobbyScreen }, stage] = await Promise.all([import('./ui/lobby'), titleStage()]);
    app.setStage(stage);
    app.show(new LocalLobbyScreen(mapId));
  },
  /** online: the lobby of the room we just joined */
  async onlineLobby(mapId: string): Promise<void> {
    const [{ OnlineLobbyScreen }, stage] = await Promise.all([import('./ui/lobby'), titleStage()]);
    app.setStage(stage);
    app.show(new OnlineLobbyScreen(mapId));
  },
  async parking(): Promise<void> {
    const [{ ParkingScreen }] = await Promise.all([import('./ui/parking')]);
    app.setStage(null);
    const screen = new ParkingScreen();
    app.setStage(screen.stage);
    app.show(screen);
  },
  async chase(): Promise<void> {
    const [{ ChaseStage }] = await Promise.all([import('./game/chase')]);
    const stage = new ChaseStage();
    app.setStage(stage);
    app.show(stage.screen);
  },
};
