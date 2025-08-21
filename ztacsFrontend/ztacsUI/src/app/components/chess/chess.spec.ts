import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Chess } from './chess';

describe('Chess', () => {
  let component: Chess;
  let fixture: ComponentFixture<Chess>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Chess]
    })
    .compileComponents();

    fixture = TestBed.createComponent(Chess);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
