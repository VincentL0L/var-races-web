// Moving between screens. Each screen sets the stage it wants behind it (the title race, or a
// map drifting under frosted glass) and shows its HTML.
import { app, type Stage } from './app';
import { DriftStage, TitleStage } from './game/backdrops';

/** the title race behind the main menu and garage (kept while moving between them) */
async function titleStage(): Promise<Stage> {
  const current = app.currentStage();
  return current instanceof TitleStage ? current : TitleStage.create();
}

/** a map drifting behind the menus (kept while moving between them, if it's the same map) */
async function driftStage(mapId = 'classic'): Promise<Stage> {
  const current = app.currentStage();
  return current instanceof DriftStage && current.mapId === mapId ? current : DriftStage.create(mapId);
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
    const [{ MapSelectScreen }, stage] = await Promise.all([import('./ui/mapSelect'), driftStage()]);
    app.setStage(stage);
    app.show(new MapSelectScreen(hosting));
  },
  async multiplayer(): Promise<void> {
    const [{ MultiplayerScreen }, stage] = await Promise.all([import('./ui/multiplayer'), driftStage()]);
    app.setStage(stage);
    app.show(new MultiplayerScreen());
  },
  async leaderboard(): Promise<void> {
    const [{ LeaderboardScreen }, stage] = await Promise.all([import('./ui/leaderboard'), driftStage()]);
    app.setStage(stage);
    app.show(new LeaderboardScreen());
  },
  /** single player: the lobby for a race on this map against the CPUs */
  async localLobby(mapId: string): Promise<void> {
    const [{ LocalLobbyScreen }, stage] = await Promise.all([import('./ui/lobby'), driftStage(mapId)]);
    app.setStage(stage);
    app.show(new LocalLobbyScreen(mapId));
  },
  /** online: the lobby of the room we just joined */
  async onlineLobby(mapId: string): Promise<void> {
    const [{ OnlineLobbyScreen }, stage] = await Promise.all([import('./ui/lobby'), driftStage(mapId)]);
    app.setStage(stage);
    app.show(new OnlineLobbyScreen(mapId));
  },
  async soccer(): Promise<void> {
    const [{ SoccerMenuScreen }, stage] = await Promise.all([import('./ui/soccer'), driftStage()]);
    app.setStage(stage);
    app.show(new SoccerMenuScreen());
  },
};
