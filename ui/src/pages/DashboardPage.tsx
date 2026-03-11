import { useEffect, useState } from 'react';
import {
  Grid,
  Column,
  Tile,
  Tag,
  SkeletonText,
} from '@carbon/react';
import { gatewayApi } from '../services/gatewayApi';

interface HealthSummary {
  status: string;
  routes: { total: number; active: number };
  plugins: { total: number; enabled: number };
}

export default function DashboardPage() {
  const [health, setHealth] = useState<HealthSummary | null>(null);
  const [actuator, setActuator] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [h, a] = await Promise.allSettled([
          gatewayApi.getHealth(),
          gatewayApi.getActuatorHealth(),
        ]);
        if (h.status === 'fulfilled') setHealth(h.value as any);
        if (a.status === 'fulfilled') setActuator(a.value);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, []);

  const statusColor = (s: string) =>
    s === 'UP' || s === 'HEALTHY' ? 'green' : s === 'DOWN' ? 'red' : 'gray';

  if (loading) {
    return (
      <Grid>
        <Column lg={16}>
          <SkeletonText heading width="40%" />
          <SkeletonText paragraph lineCount={4} />
        </Column>
      </Grid>
    );
  }

  return (
    <Grid>
      <Column lg={16} md={8} sm={4}>
        <h1 style={{ marginBottom: '1.5rem' }}>Gateway Dashboard</h1>
      </Column>

      <Column lg={4} md={4} sm={4}>
        <Tile style={{ marginBottom: '1rem' }}>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d', marginBottom: '0.5rem' }}>
            Gateway Status
          </p>
          <Tag type={statusColor(actuator?.status || health?.status || 'UNKNOWN')}>
            {actuator?.status || health?.status || 'UNKNOWN'}
          </Tag>
        </Tile>
      </Column>

      <Column lg={4} md={4} sm={4}>
        <Tile style={{ marginBottom: '1rem' }}>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d', marginBottom: '0.5rem' }}>Routes</p>
          <h3>{health?.routes?.active ?? '—'} / {health?.routes?.total ?? '—'}</h3>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d' }}>active</p>
        </Tile>
      </Column>

      <Column lg={4} md={4} sm={4}>
        <Tile style={{ marginBottom: '1rem' }}>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d', marginBottom: '0.5rem' }}>Plugins</p>
          <h3>{health?.plugins?.enabled ?? '—'} / {health?.plugins?.total ?? '—'}</h3>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d' }}>enabled</p>
        </Tile>
      </Column>

      <Column lg={4} md={4} sm={4}>
        <Tile style={{ marginBottom: '1rem' }}>
          <p style={{ fontSize: '0.75rem', color: '#8d8d8d', marginBottom: '0.5rem' }}>
            Actuator
          </p>
          <Tag type={statusColor(actuator?.status || 'UNKNOWN')}>
            {actuator?.status || 'UNKNOWN'}
          </Tag>
        </Tile>
      </Column>

      {actuator?.components && (
        <Column lg={16} md={8} sm={4}>
          <Tile style={{ marginTop: '1rem' }}>
            <h4 style={{ marginBottom: '1rem' }}>Health Components</h4>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
              {Object.entries(actuator.components).map(([key, val]: [string, any]) => (
                <Tag key={key} type={statusColor(val.status)}>
                  {key}: {val.status}
                </Tag>
              ))}
            </div>
          </Tile>
        </Column>
      )}
    </Grid>
  );
}
