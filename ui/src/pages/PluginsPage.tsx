import { useEffect, useState } from 'react';
import {
  Grid,
  Column,
  Tile,
  Tag,
  Toggle,
  SkeletonText,
  InlineNotification,
} from '@carbon/react';
import { gatewayApi } from '../services/gatewayApi';
import type { GatewayPlugin } from '../types';

export default function PluginsPage() {
  const [plugins, setPlugins] = useState<GatewayPlugin[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [toggling, setToggling] = useState<string | null>(null);

  useEffect(() => {
    fetchPlugins();
  }, []);

  const fetchPlugins = async () => {
    try {
      const data = await gatewayApi.getPlugins();
      const items = Array.isArray(data)
        ? data
        : typeof data === 'object'
        ? Object.entries(data).map(([key, val]: [string, any]) => ({
            id: key,
            name: val.name || key,
            ...val,
          }))
        : [];
      setPlugins(items);
    } catch (err: any) {
      setError(err.message || 'Failed to load plugins');
    } finally {
      setLoading(false);
    }
  };

  const handleToggle = async (plugin: GatewayPlugin) => {
    setToggling(plugin.id);
    try {
      if (plugin.enabled) {
        await gatewayApi.disablePlugin(plugin.id);
      } else {
        await gatewayApi.enablePlugin(plugin.id);
      }
      await fetchPlugins();
    } catch (err: any) {
      setError(`Failed to toggle ${plugin.name}: ${err.message}`);
    } finally {
      setToggling(null);
    }
  };

  if (loading) {
    return (
      <Grid>
        <Column lg={16}>
          <SkeletonText heading width="30%" />
          <SkeletonText paragraph lineCount={4} />
        </Column>
      </Grid>
    );
  }

  return (
    <Grid>
      <Column lg={16} md={8} sm={4}>
        <h1 style={{ marginBottom: '1.5rem' }}>Gateway Plugins</h1>
      </Column>

      {error && (
        <Column lg={16}>
          <InlineNotification
            kind="error"
            title="Error"
            subtitle={error}
            onCloseButtonClick={() => setError('')}
            style={{ marginBottom: '1rem' }}
          />
        </Column>
      )}

      {plugins.length === 0 ? (
        <Column lg={16}>
          <Tile>
            <p style={{ color: '#8d8d8d' }}>No plugins registered</p>
          </Tile>
        </Column>
      ) : (
        plugins.map((plugin) => (
          <Column key={plugin.id} lg={8} md={4} sm={4}>
            <Tile style={{ marginBottom: '1rem', minHeight: '10rem' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <h4>{plugin.name || plugin.id}</h4>
                  <p style={{ fontSize: '0.75rem', color: '#8d8d8d', marginTop: '0.25rem' }}>
                    {plugin.description || `Order: ${plugin.order ?? '—'} | Phase: ${plugin.phase ?? '—'}`}
                  </p>
                </div>
                <Toggle
                  id={`toggle-${plugin.id}`}
                  size="sm"
                  toggled={plugin.enabled}
                  disabled={toggling === plugin.id}
                  onToggle={() => handleToggle(plugin)}
                  labelA="Off"
                  labelB="On"
                  hideLabel
                />
              </div>
              <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                <Tag type={plugin.enabled ? 'green' : 'gray'}>
                  {plugin.enabled ? 'Enabled' : 'Disabled'}
                </Tag>
                {plugin.phase && <Tag type="blue">{plugin.phase}</Tag>}
                {plugin.order != null && <Tag type="cool-gray">Order: {plugin.order}</Tag>}
                {plugin.healthy !== undefined && (
                  <Tag type={plugin.healthy ? 'green' : 'red'}>
                    {plugin.healthy ? 'Healthy' : 'Unhealthy'}
                  </Tag>
                )}
              </div>
            </Tile>
          </Column>
        ))
      )}
    </Grid>
  );
}
