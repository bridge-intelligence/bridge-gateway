import { useEffect, useState } from 'react';
import {
  Grid,
  Column,
  Tile,
  Tag,
  SkeletonText,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListRow,
  StructuredListCell,
  StructuredListBody,
} from '@carbon/react';
import { gatewayApi } from '../services/gatewayApi';

export default function HealthPage() {
  const [actuator, setActuator] = useState<any>(null);
  const [info, setInfo] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetch = async () => {
      try {
        const [h, i] = await Promise.allSettled([
          gatewayApi.getActuatorHealth(),
          gatewayApi.getActuatorInfo(),
        ]);
        if (h.status === 'fulfilled') setActuator(h.value);
        if (i.status === 'fulfilled') setInfo(i.value);
      } finally {
        setLoading(false);
      }
    };
    fetch();
  }, []);

  const statusColor = (s: string) =>
    s === 'UP' ? 'green' : s === 'DOWN' ? 'red' : 'gray';

  if (loading) {
    return (
      <Grid>
        <Column lg={16}>
          <SkeletonText heading width="30%" />
          <SkeletonText paragraph lineCount={8} />
        </Column>
      </Grid>
    );
  }

  const components = actuator?.components
    ? Object.entries(actuator.components)
    : [];

  return (
    <Grid>
      <Column lg={16} md={8} sm={4}>
        <h1 style={{ marginBottom: '1.5rem' }}>System Health</h1>
      </Column>

      <Column lg={8} md={4} sm={4}>
        <Tile style={{ marginBottom: '1rem' }}>
          <h4 style={{ marginBottom: '1rem' }}>Overall Status</h4>
          <Tag type={statusColor(actuator?.status || 'UNKNOWN')} size="md">
            {actuator?.status || 'UNKNOWN'}
          </Tag>
        </Tile>
      </Column>

      {info && (
        <Column lg={8} md={4} sm={4}>
          <Tile style={{ marginBottom: '1rem' }}>
            <h4 style={{ marginBottom: '1rem' }}>Build Info</h4>
            <pre style={{ fontSize: '0.75rem', color: '#c6c6c6', whiteSpace: 'pre-wrap' }}>
              {JSON.stringify(info, null, 2)}
            </pre>
          </Tile>
        </Column>
      )}

      {components.length > 0 && (
        <Column lg={16} md={8} sm={4}>
          <Tile>
            <h4 style={{ marginBottom: '1rem' }}>Health Components</h4>
            <StructuredListWrapper>
              <StructuredListHead>
                <StructuredListRow head>
                  <StructuredListCell head>Component</StructuredListCell>
                  <StructuredListCell head>Status</StructuredListCell>
                  <StructuredListCell head>Details</StructuredListCell>
                </StructuredListRow>
              </StructuredListHead>
              <StructuredListBody>
                {components.map(([name, val]: [string, any]) => (
                  <StructuredListRow key={name}>
                    <StructuredListCell>{name}</StructuredListCell>
                    <StructuredListCell>
                      <Tag type={statusColor(val.status)}>{val.status}</Tag>
                    </StructuredListCell>
                    <StructuredListCell>
                      {val.details ? (
                        <pre style={{ fontSize: '0.7rem', color: '#a8a8a8', margin: 0, whiteSpace: 'pre-wrap' }}>
                          {JSON.stringify(val.details, null, 2)}
                        </pre>
                      ) : (
                        '—'
                      )}
                    </StructuredListCell>
                  </StructuredListRow>
                ))}
              </StructuredListBody>
            </StructuredListWrapper>
          </Tile>
        </Column>
      )}
    </Grid>
  );
}
