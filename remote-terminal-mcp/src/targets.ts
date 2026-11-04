import type { Configuration, ConnectInfo, ListedTarget, TargetSource } from './transport.js';

/** Local mode: the configuration file is the whole whitelist. */
export class StaticSource implements TargetSource {
  constructor(private readonly config: Configuration) {}
  async list(): Promise<ListedTarget[]> {
    return this.config.targets.map(target => ({ target_id: target.target_id, alias: target.alias, environment: target.environment,
      host: target.host, port: target.port, host_key: target.host_key,
      profiles: target.profiles.map(profile => ({ profile_id: profile.profile_id, login_user: profile.login_user, max_ttl_seconds: profile.max_ttl_seconds })) }));
  }
  async connectInfo(targetId: string, profileId: string): Promise<ConnectInfo> {
    const target = this.config.targets.find(item => item.target_id === targetId);
    const profile = target?.profiles.find(item => item.profile_id === profileId);
    if (!target || !profile) throw new Error('Configured server identity is unavailable');
    return { target, profile };
  }
}
