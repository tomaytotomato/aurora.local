import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Table from './Table.vue';
import TableHeader from './TableHeader.vue';
import TableBody from './TableBody.vue';
import TableRow from './TableRow.vue';
import TableHead from './TableHead.vue';
import TableCell from './TableCell.vue';

/**
 * C10 iter-17: shadcn-vue Table sub-primitives.
 *
 * Six tiny wrapper components: Table + TableHeader + TableBody +
 * TableRow + TableHead + TableCell. The value here is a
 * consistent visual (shadcn tokens, hover-on-row, border-b between
 * rows, horizontal overflow scroll) not fancy interaction — sorting,
 * pagination, and column-picker stay in view code as needed.
 *
 * Tests pin the token contract + the semantic wrapping (thead/tbody/
 * tr/th/td) so a future edit can't silently regress a table row
 * into a <div class="grid">-flavoured layout.
 */

describe('Table', () => {
  it('renders a <table> wrapped in an overflow container', () => {
    const w = mount(Table, { slots: { default: '<tbody><tr><td>x</td></tr></tbody>' } });
    // Root is the overflow wrapper; the actual <table> is inside.
    expect(w.element.tagName).toBe('DIV');
    expect(w.attributes('data-slot')).toBe('table-wrapper');
    expect(w.get('table').attributes('data-slot')).toBe('table');
    expect(w.get('table').classes().join(' ')).toContain('caption-bottom');
  });

  it('merges caller class on the <table>', () => {
    const w = mount(Table, {
      props: { class: 'font-mono text-xs' },
      slots: { default: '<tbody><tr><td>x</td></tr></tbody>' },
    });
    const cls = w.get('table').classes().join(' ');
    expect(cls).toContain('font-mono');
    expect(cls).toContain('text-xs');
  });
});

describe('TableHeader / TableBody', () => {
  it('renders <thead> and <tbody> with data-slot markers', () => {
    // Vue silently normalises invalid table nesting at runtime; mount
    // TableHeader / TableBody standalone to inspect their DOM.
    const h = mount(TableHeader, {
      slots: { default: '<tr><th>a</th></tr>' },
    });
    expect(h.element.tagName).toBe('THEAD');
    expect(h.attributes('data-slot')).toBe('table-header');
    const cls = h.classes().join(' ');
    expect(cls).toContain('[&_tr]:border-b');

    const b = mount(TableBody, {
      slots: { default: '<tr><td>a</td></tr>' },
    });
    expect(b.element.tagName).toBe('TBODY');
    expect(b.attributes('data-slot')).toBe('table-body');
    expect(b.classes().join(' ')).toContain('[&_tr:last-child]:border-0');
  });
});

describe('TableRow', () => {
  it('renders a <tr> with shadcn hover + selected tokens', () => {
    const w = mount(TableRow, { slots: { default: '<td>a</td>' } });
    expect(w.element.tagName).toBe('TR');
    expect(w.attributes('data-slot')).toBe('table-row');
    const cls = w.classes().join(' ');
    expect(cls).toContain('border-border');
    expect(cls).toContain('hover:bg-muted/50');
    expect(cls).toContain('data-[state=selected]:bg-muted');
  });

  it('merges caller class', () => {
    const w = mount(TableRow, {
      props: { class: 'hover:bg-transparent' },
      slots: { default: '<td>a</td>' },
    });
    // tailwind-merge will drop the default hover:bg-muted/50 in
    // favour of the caller's hover:bg-transparent — that's the
    // whole point of using cn().
    const cls = w.classes().join(' ');
    expect(cls).toContain('hover:bg-transparent');
    expect(cls).not.toContain('hover:bg-muted/50');
  });
});

describe('TableHead / TableCell', () => {
  it('TableHead renders a <th> with muted-foreground', () => {
    const w = mount(TableHead, { slots: { default: 'Time' } });
    expect(w.element.tagName).toBe('TH');
    expect(w.attributes('data-slot')).toBe('table-head');
    const cls = w.classes().join(' ');
    expect(cls).toContain('text-muted-foreground');
    expect(cls).toContain('font-medium');
    expect(cls).toContain('h-10');
    expect(w.text()).toBe('Time');
  });

  it('TableCell renders a <td> with align-middle', () => {
    const w = mount(TableCell, { slots: { default: 'sec.dismiss' } });
    expect(w.element.tagName).toBe('TD');
    expect(w.attributes('data-slot')).toBe('table-cell');
    expect(w.classes().join(' ')).toContain('align-middle');
    expect(w.text()).toBe('sec.dismiss');
  });

  it('TableHead and TableCell merge caller class', () => {
    const h = mount(TableHead, { props: { class: 'w-40' } });
    expect(h.classes().join(' ')).toContain('w-40');

    const c = mount(TableCell, { props: { class: 'text-foreground' } });
    expect(c.classes().join(' ')).toContain('text-foreground');
  });
});
